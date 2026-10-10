#include <android/log.h>
#include <jni.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "prompt_cache.h"

namespace {
constexpr const char *TAG = "DinaLLM";
std::mutex g_mutex;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_sampler *g_grammar = nullptr;  // null: unconstrained greedy
std::vector<llama_token_data> g_candidates;
bool g_complete = false;
PromptCache g_cache;  // reuses the system prompt and previous prompts across generations
int g_reused_tokens = 0;
const llama_vocab *g_vocab = nullptr;
bool g_generating = false;
bool g_cancelled = false;
int g_max_tokens = 0;
int g_generated = 0;
int g_prompt_tokens = 0;
int64_t g_begin_us = 0;
int64_t g_prefill_end_us = 0;
int64_t g_first_token_us = 0;
int64_t g_tokenization_end_us = 0;
int64_t g_generation_end_us = 0;
std::string g_pending_utf8;  // bytes of a character split across tokens, waiting for the rest

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void log_callback(enum ggml_log_level level, const char *text, void *) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    __android_log_write(priority, TAG, text);
}

// Length of the longest prefix of [bytes] made of complete UTF-8 characters: a token may end in the
// middle of one (accents, emojis) and the rest arrives with the next token.
size_t complete_utf8(const std::string &bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const auto lead = static_cast<unsigned char>(bytes[i]);
        const size_t length = lead < 0x80 ? 1 : (lead >> 5) == 0x6 ? 2 : (lead >> 4) == 0xE ? 3 : (lead >> 3) == 0x1E ? 4 : 1;
        if (i + length > bytes.size()) break;
        i += length;
    }
    return i;
}

// Java string from UTF-8 (NewStringUTF expects modified UTF-8 and rejects 4-byte characters).
jstring java_string(JNIEnv *env, const std::string &utf8) {
    std::u16string utf16;
    utf16.reserve(utf8.size());
    for (size_t i = 0; i < utf8.size();) {
        const auto lead = static_cast<unsigned char>(utf8[i]);
        uint32_t code = 0xFFFD;
        size_t length = 1;
        if (lead < 0x80) code = lead;
        else if ((lead >> 5) == 0x6 && i + 1 < utf8.size()) { code = ((lead & 0x1F) << 6) | (utf8[i + 1] & 0x3F); length = 2; }
        else if ((lead >> 4) == 0xE && i + 2 < utf8.size()) { code = ((lead & 0x0F) << 12) | ((utf8[i + 1] & 0x3F) << 6) | (utf8[i + 2] & 0x3F); length = 3; }
        else if ((lead >> 3) == 0x1E && i + 3 < utf8.size()) {
            code = ((lead & 0x07) << 18) | ((utf8[i + 1] & 0x3F) << 12) | ((utf8[i + 2] & 0x3F) << 6) | (utf8[i + 3] & 0x3F);
            length = 4;
        }
        if (code >= 0x10000) {
            code -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xD800 + (code >> 10)));
            utf16.push_back(static_cast<char16_t>(0xDC00 + (code & 0x3FF)));
        } else {
            utf16.push_back(static_cast<char16_t>(code));
        }
        i += length;
    }
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

void free_sampler() {
    if (g_grammar) {
        llama_sampler_free(g_grammar);
        g_grammar = nullptr;
    }
}

// Greedy over the tokens the grammar allows: exactly what a grammar + greedy sampler chain picks,
// without running the grammar over the whole vocabulary on every token. The unconstrained best
// token is checked first; when the grammar allows it, it is also the best allowed token. Only when
// it is rejected is every token filtered (llama.cpp's grammar judges each candidate on its own).
llama_token sample_greedy() {
    const float *logits = llama_get_logits_ith(g_context, -1);
    const int n_vocab = llama_vocab_n_tokens(g_vocab);
    llama_token best = 0;  // first maximum, like llama_sampler_init_greedy
    for (llama_token i = 1; i < n_vocab; ++i) if (logits[i] > logits[best]) best = i;
    if (!g_grammar) return best;

    llama_token_data single = {best, logits[best], 0.0f};
    llama_token_data_array one = {&single, 1, -1, false};
    llama_sampler_apply(g_grammar, &one);
    if (single.logit == -INFINITY) {
        g_candidates.resize(static_cast<size_t>(n_vocab));
        for (llama_token i = 0; i < n_vocab; ++i) g_candidates[i] = {i, logits[i], 0.0f};
        llama_token_data_array all = {g_candidates.data(), g_candidates.size(), -1, false};
        llama_sampler_apply(g_grammar, &all);
        size_t pick = 0;
        for (size_t i = 1; i < all.size; ++i) if (all.data[i].logit > all.data[pick].logit) pick = i;
        best = all.data[pick].id;
    }
    llama_sampler_accept(g_grammar, best);
    return best;
}

void free_all() {
    free_sampler();
    g_cache.clear();
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_generating = false;
}

}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeLoad(
    JNIEnv *env, jobject, jstring path, jint threads, jint context_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();
    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    const char *model_path = env->GetStringUTFChars(path, nullptr);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(path, model_path);
    if (!g_model) return JNI_FALSE;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size);
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = false;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        free_all();
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeBegin(
    JNIEnv *env, jobject, jstring prompt_string, jint max_tokens, jstring grammar_string) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vocab) return JNI_FALSE;
    const char *chars = env->GetStringUTFChars(prompt_string, nullptr);
    std::string prompt(chars);
    env->ReleaseStringUTFChars(prompt_string, chars);

    g_begin_us = now_us();
    g_prefill_end_us = 0;
    g_first_token_us = 0;
    g_tokenization_end_us = 0;
    g_generation_end_us = 0;
    g_generated = 0;
    g_complete = false;
    g_max_tokens = max_tokens;
    g_cancelled = false;
    g_generating = false;
    g_pending_utf8.clear();
    free_sampler();
    if (grammar_string != nullptr) {
        const char *grammar_chars = env->GetStringUTFChars(grammar_string, nullptr);
        g_grammar = llama_sampler_init_grammar(g_vocab, grammar_chars, "root");
        env->ReleaseStringUTFChars(grammar_string, grammar_chars);
        if (!g_grammar) return JNI_FALSE;
    }

    const int token_count = -llama_tokenize(g_vocab, prompt.data(), prompt.size(), nullptr, 0, false, true);
    if (token_count <= 0 || token_count + max_tokens >= static_cast<int>(llama_n_ctx(g_context))) return JNI_FALSE;
    std::vector<llama_token> tokens(token_count);
    if (llama_tokenize(g_vocab, prompt.data(), prompt.size(), tokens.data(), token_count, false, true) < 0) return JNI_FALSE;
    g_tokenization_end_us = now_us();
    g_prompt_tokens = token_count;
    if (!g_cache.prefill(g_context, g_vocab, tokens, 512)) {
        g_cache.clear();
        return JNI_FALSE;
    }
    g_reused_tokens = g_cache.reused();
    g_prefill_end_us = now_us();
    g_generating = true;
    return JNI_TRUE;
}

// Prefills the beginning of the next prompt ahead of time (see PromptCache::warm).
// Returns the tokens decoded (0 when already warm) or -1 on failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeWarm(JNIEnv *env, jobject, jstring prefix_string) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vocab || g_generating) return -1;
    const char *chars = env->GetStringUTFChars(prefix_string, nullptr);
    std::string prefix(chars);
    env->ReleaseStringUTFChars(prefix_string, chars);
    const int token_count = -llama_tokenize(g_vocab, prefix.data(), prefix.size(), nullptr, 0, false, true);
    if (token_count <= 0 || token_count >= static_cast<int>(llama_n_ctx(g_context))) return -1;
    std::vector<llama_token> tokens(token_count);
    if (llama_tokenize(g_vocab, prefix.data(), prefix.size(), tokens.data(), token_count, false, true) < 0) return -1;
    return g_cache.warm(g_context, g_vocab, tokens, 512);
}

// Threads for decoding and for prompt processing; takes effect on the next call.
extern "C" JNIEXPORT void JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeSetThreads(JNIEnv *, jobject, jint threads, jint batch_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_context) llama_set_n_threads(g_context, threads, batch_threads);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeNext(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_generating || g_cancelled || g_generated >= g_max_tokens) {
        g_generating = false;
        g_generation_end_us = now_us();
        return nullptr;
    }
    const llama_token token = sample_greedy();
    if (llama_vocab_is_eog(g_vocab, token)) {
        g_complete = true;
        g_generating = false;
        g_generation_end_us = now_us();
        return nullptr;
    }
    if (g_first_token_us == 0) g_first_token_us = now_us();
    char buffer[256];
    int length = llama_token_to_piece(g_vocab, token, buffer, sizeof(buffer), 0, true);
    std::string piece;
    if (length < 0) {
        piece.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(g_vocab, token, piece.data(), piece.size(), 0, true);
        if (length < 0) return nullptr;
        piece.resize(static_cast<size_t>(length));
    } else {
        piece.assign(buffer, static_cast<size_t>(length));
    }
    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
    if (llama_decode(g_context, batch) != 0) {
        g_generating = false;
        return nullptr;
    }
    ++g_generated;
    // Only whole characters go to Java; an empty piece still counts as a token (null ends the generation).
    g_pending_utf8 += piece;
    const size_t complete = complete_utf8(g_pending_utf8);
    const std::string ready = g_pending_utf8.substr(0, complete);
    g_pending_utf8.erase(0, complete);
    return java_string(env, ready);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeCancel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancelled = true;
    g_generating = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeStats(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const int64_t end_us = now_us();
    const double prefill_ms = g_prefill_end_us > g_begin_us ? (g_prefill_end_us - g_begin_us) / 1000.0 : 0.0;
    const double tokenization_ms = g_tokenization_end_us > g_begin_us ? (g_tokenization_end_us - g_begin_us) / 1000.0 : 0.0;
    const double prompt_processing_ms = g_prefill_end_us > g_tokenization_end_us ? (g_prefill_end_us - g_tokenization_end_us) / 1000.0 : 0.0;
    const double first_ms = g_first_token_us > g_begin_us ? (g_first_token_us - g_begin_us) / 1000.0 : 0.0;
    const double decode_s = g_first_token_us > 0 ? std::max(0.000001, (end_us - g_first_token_us) / 1000000.0) : 0.0;
    const double generation_ms = g_generation_end_us > g_first_token_us ? (g_generation_end_us - g_first_token_us) / 1000.0 : decode_s * 1000.0;
    const double tps = decode_s > 0 ? g_generated / decode_s : 0.0;
    std::ostringstream out;
    out << "{\"promptTokens\":" << g_prompt_tokens
        << ",\"reusedPromptTokens\":" << g_reused_tokens
        << ",\"completionTokens\":" << g_generated
        << ",\"prefillMs\":" << prompt_processing_ms
        << ",\"tokenizationMs\":" << tokenization_ms
        << ",\"firstTokenMs\":" << first_ms
        << ",\"generationMs\":" << generation_ms
        << ",\"tokensPerSecond\":" << tps
        << ",\"complete\":" << (g_complete ? "true" : "false") << "}";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakauet_dina_llm_NativeLlmEngine_nativeClose(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();
    llama_backend_free();
}
