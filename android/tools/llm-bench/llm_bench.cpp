// Replays a realistic conversation with the app's PromptCache and checks it is exact.
//
// Three voice turns as Dina45Brain runs them (one call per turn; state, previous turn and phrase):
//   full   - every generation prefills the whole prompt;
//   cached - PromptCache reuses the system prompt;
//   warm   - plus the prompt up to the phrase prefilled while the user speaks (PromptCache::warm):
//            the time shown is what is left once the transcript arrives.
// Prints prefill and decoding time per call, and checks that restoring a snapshot gives the same
// output as decoding from scratch with the same boundaries.
//
// usage: llm_bench <model.gguf> [threads]

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "llama.h"
#include "prompt_cache.h"

namespace {
// Must match brain/dina45/Dina45Prompt.kt.
const char *SYSTEM = "Eres Dina, asistente de voz local. Lee el estado y la frase y escribe solo acciones, una por línea: dominio.op(objetivo, valor, clave=valor). Sin petición: say(\"…\"). Si no sabes qué quiere: ask(). Si no puedes: no(tema).";

// Dina45Prompt.prefix / Dina45Prompt.complete.
std::string prompt_prefix(const std::string &state, const std::string &before) {
    std::string out = std::string("<|startoftext|><|im_start|>system\n") + SYSTEM + "<|im_end|>\n";
    out += "<|im_start|>user\n[estado]\n" + state + "\n";
    if (!before.empty()) out += "[antes]\n" + before + "\n";
    return out + "[ahora]\n";
}

std::string prompt_complete(const std::string &prefix, const std::string &phrase) {
    return prefix + phrase + "<|im_end|>\n<|im_start|>assistant\n";
}

double ms_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

struct Call {
    int prompt_tokens; int reused; double prefill_ms; double decode_ms; int completion_tokens;
    std::string output; std::string prompt; std::vector<size_t> splits;
};

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    std::vector<llama_token> tokens(text.size() + 16);
    tokens.resize(llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), tokens.data(), static_cast<int>(tokens.size()), false, true));
    return tokens;
}

class Runner {
public:
    Runner(llama_model *model, int threads, bool use_cache) : use_cache_(use_cache) {
        auto params = llama_context_default_params();
        params.n_ctx = 1536;  // as on the phone
        params.n_batch = 512;
        params.n_ubatch = 512;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        ctx_ = llama_init_from_model(model, params);
        vocab_ = llama_model_get_vocab(model);
    }
    ~Runner() { llama_free(ctx_); }

    // The app's warm-up (Dina45Brain.prepare): returns the tokens decoded.
    int warm(const std::string &prefix) {
        if (!use_cache_) return 0;
        const int decoded = cache_.warm(ctx_, vocab_, tokenize(vocab_, prefix), 512);
        if (decoded < 0) { std::fprintf(stderr, "warm failed\n"); std::exit(2); }
        return decoded;
    }

    Call generate(const std::string &prompt, int max_tokens) {
        const std::vector<llama_token> tokens = tokenize(vocab_, prompt);
        if (!use_cache_) cache_.clear();
        const auto start = std::chrono::steady_clock::now();
        if (!cache_.prefill(ctx_, vocab_, tokens, 512)) { std::fprintf(stderr, "prefill failed\n"); std::exit(2); }
        Call call{static_cast<int>(tokens.size()), cache_.reused(), ms_since(start), 0.0, 0, "", prompt, cache_.splits()};
        const auto decode_start = std::chrono::steady_clock::now();
        call.output = sample(max_tokens, &call.completion_tokens);
        call.decode_ms = ms_since(decode_start);
        return call;
    }

    // Decodes `prompt` from scratch with the given boundaries, then generates.
    std::string replay(const std::string &prompt, const std::vector<size_t> &splits, int max_tokens) {
        if (!PromptCache::decode_with_splits(ctx_, tokenize(vocab_, prompt), splits, 512)) { std::fprintf(stderr, "replay failed\n"); std::exit(2); }
        int count = 0;
        return sample(max_tokens, &count);
    }

private:
    std::string sample(int max_tokens, int *count) {
        std::string output;
        llama_sampler *sampler = llama_sampler_init_greedy();
        for (int i = 0; i < max_tokens; ++i) {
            llama_token token = llama_sampler_sample(sampler, ctx_, -1);
            if (llama_vocab_is_eog(vocab_, token)) break;
            char piece[256];
            const int len = llama_token_to_piece(vocab_, token, piece, sizeof(piece), 0, true);
            if (len > 0) output.append(piece, len);
            ++*count;
            llama_batch batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx_, batch) != 0) break;
        }
        llama_sampler_free(sampler);
        return output;
    }

    bool use_cache_;
    llama_context *ctx_;
    const llama_vocab *vocab_;
    PromptCache cache_;
};

// Three voice turns as Dina45Brain runs them; with `warm`, the prefix is prefilled first (off the clock).
std::vector<Call> conversation(Runner &runner, bool warm) {
    struct Turn { const char *state; const char *before; const char *phrase; };
    const Turn turns[] = {
        {"hora: mié 7 oct, 9:30", "", "pon un temporizador de cinco minutos para la pasta"},
        {"hora: mié 7 oct, 9:30\ntemporizadores: «pasta» 4:52\nfoco: temporizador «pasta»",
         "usuario: pon un temporizador de cinco minutos para la pasta\ndina: timer.add(5m, \"pasta\") → hecho",
         "¿cuánto le queda?"},
        {"hora: mié 7 oct, 9:31\ntemporizadores: «pasta» 4:31\ncompra: leche · pan\nfoco: temporizador «pasta»",
         "usuario: ¿cuánto le queda?\ndina: timer.get(\"pasta\") → 4:40",
         "añade también huevos y quita el pan"},
    };
    std::vector<Call> calls;
    for (const auto &turn : turns) {
        const std::string prefix = prompt_prefix(turn.state, turn.before);
        if (warm) runner.warm(prefix);
        calls.push_back(runner.generate(prompt_complete(prefix, turn.phrase), 48));
    }
    return calls;
}
}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: llm_bench <model.gguf> [threads]\n"); return 1; }
    const int threads = argc > 2 ? std::atoi(argv[2]) : 6;
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(argv[1], model_params);
    if (!model) { std::fprintf(stderr, "cannot load %s\n", argv[1]); return 1; }

    { Runner warmup(model, threads, false); conversation(warmup, false); }  // page the weights in
    std::vector<Call> full, cached, warm;
    { Runner runner(model, threads, false); full = conversation(runner, false); }
    { Runner runner(model, threads, true); cached = conversation(runner, false); }
    { Runner runner(model, threads, true); warm = conversation(runner, true); }

    // Exactness: a snapshot restore must equal decoding from scratch with the same boundaries.
    bool exact = true;
    for (const auto *mode : {&cached, &warm}) {
        Runner reference(model, threads, false);
        for (size_t i = 0; i < mode->size(); ++i) {
            const std::string again = reference.replay((*mode)[i].prompt, (*mode)[i].splits, 48);
            if (again != (*mode)[i].output) { exact = false; std::printf("replay %zu differs: %s\n", i + 1, again.c_str()); }
        }
    }

    auto total = [](const std::vector<Call> &calls, bool decode) {
        double sum = 0;
        for (const auto &c : calls) sum += decode ? c.decode_ms : c.prefill_ms;
        return sum;
    };
    std::printf("Dina 4.5, %d threads\n", threads);
    std::printf("call  tokens  out | full: prefill decode | cached: reused prefill | warm: reused prefill\n");
    bool same = true;
    for (size_t i = 0; i < full.size(); ++i) {
        const bool equal = full[i].output == cached[i].output && full[i].output == warm[i].output;
        same = same && equal;
        std::printf("%4zu  %6d  %3d | %7.0f %6.0f | %6d %7.0f | %6d %7.0f", i + 1, full[i].prompt_tokens, full[i].completion_tokens,
                    full[i].prefill_ms, full[i].decode_ms, cached[i].reused, cached[i].prefill_ms, warm[i].reused, warm[i].prefill_ms);
        std::printf("  %s\n", equal ? "same" : "DIFFERS");
    }
    std::printf("prefill total: full %.0f ms, cached %.0f ms, warm %.0f ms (after the transcript)",
                total(full, false), total(cached, false), total(warm, false));
    std::printf("; decoding %.0f ms for %zu calls\n", total(full, true), full.size());
    for (size_t i = 0; i < cached.size(); ++i) {
        std::printf("out %zu: %s\n", i + 1, cached[i].output.c_str());
        if (full[i].output != cached[i].output) std::printf("out %zu full: %s\n", i + 1, full[i].output.c_str());
        if (warm[i].output != cached[i].output) std::printf("out %zu warm: %s\n", i + 1, warm[i].output.c_str());
    }
    std::printf("restore exact (same boundaries): %s\n", exact ? "yes" : "NO");
    std::printf("%s\n", same ? "identical to one-shot prefill" : "differs from one-shot prefill (batch-size numerics)");
    llama_model_free(model);
    llama_backend_free();
    return exact ? 0 : 3;
}
