#pragma once

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <vector>

#include "llama.h"

// Reuses prefill work across generations by snapshotting the sequence state.
//
// LFM2.5 is a hybrid model (attention + short convolutions): its recurrent state cannot be
// truncated like a KV cache, so a common prefix is reused by restoring a full state snapshot.
// Two snapshots are kept:
//   - anchor: the first chat message (the fixed system prompt), identical in every call;
//   - last:   the previous prompt minus its final token, or the prefix prepared by warm().
// A snapshot is restored only when its tokens are an exact prefix of the new prompt, so the
// result is the same as a full prefill; it only skips work.
//
// warm() prefills the start of a prompt that is not complete yet (Dina 4.5: everything before the
// user's phrase, while the user is still speaking) and keeps it as `last`, so the real prompt
// only has to decode the phrase.
class PromptCache {
public:
    // Fills the context with `tokens` (sequence 0), ready to sample the next token.
    // Returns false if decoding fails; the context must then be considered empty.
    bool prefill(llama_context *ctx, const llama_vocab *vocab, const std::vector<llama_token> &tokens, int batch) {
        const size_t n = tokens.size();
        if (n == 0 || !extend(ctx, vocab, tokens, n - 1, batch)) return false;
        save(ctx, last_, tokens, n - 1);
        splits_.push_back(n);
        return decode(ctx, tokens, n - 1, n, batch);
    }

    // Decodes `tokens` (the beginning of a later prompt) and keeps all of them as `last`.
    // Returns the number of tokens decoded (0 when already warm), or -1 if decoding fails.
    int warm(llama_context *ctx, const llama_vocab *vocab, const std::vector<llama_token> &tokens, int batch) {
        const size_t n = tokens.size();
        if (n == 0) return 0;
        if (last_.tokens == tokens && !last_.state.empty()) return 0;
        if (!extend(ctx, vocab, tokens, n, batch)) {
            clear();
            return -1;
        }
        const int decoded = static_cast<int>(n) - reused_;
        save(ctx, last_, tokens, n);
        return decoded;
    }

    void clear() {
        anchor_ = Snapshot{};
        last_ = Snapshot{};
        reused_ = 0;
    }

    // Prompt tokens restored from a snapshot in the last prefill.
    int reused() const { return reused_; }

    // Decode boundaries that produced the current state (for exactness checks: decoding the same
    // prompt from scratch with these boundaries must give bit-identical results).
    const std::vector<size_t> &splits() const { return splits_; }

    // Same chunking as prefill(), without snapshots.
    static bool decode_with_splits(llama_context *ctx, const std::vector<llama_token> &tokens, const std::vector<size_t> &splits, int batch) {
        llama_memory_clear(llama_get_memory(ctx), false);
        for (size_t i = 1; i < splits.size(); ++i) if (!decode(ctx, tokens, splits[i - 1], splits[i], batch)) return false;
        return true;
    }

private:
    struct Snapshot {
        std::vector<llama_token> tokens;
        std::vector<uint8_t> state;
        std::vector<size_t> splits;
    };

    Snapshot anchor_;
    Snapshot last_;
    int reused_ = 0;
    std::vector<size_t> splits_;

    // Restores the longest snapshot that is a strict prefix of `tokens` (shorter than `end`, a
    // snapshot never covers the token to be sampled from) and decodes up to `end`, saving the
    // anchor on the way.
    bool extend(llama_context *ctx, const llama_vocab *vocab, const std::vector<llama_token> &tokens, size_t end, int batch) {
        reused_ = 0;
        splits_.assign(1, 0);
        llama_memory_t memory = llama_get_memory(ctx);
        llama_memory_clear(memory, false);
        const size_t n = tokens.size();

        size_t done = 0;
        const Snapshot *best = nullptr;
        for (const Snapshot *candidate : {&last_, &anchor_}) {
            if (candidate->state.empty() || candidate->tokens.size() > end || candidate->tokens.size() >= n ||
                !starts_with(tokens, candidate->tokens)) continue;
            if (!best || candidate->tokens.size() > best->tokens.size()) best = candidate;
        }
        if (best && llama_state_seq_set_data(ctx, best->state.data(), best->state.size(), 0) == best->state.size()) {
            done = best->tokens.size();
            reused_ = static_cast<int>(done);
            splits_ = best->splits;
        } else {
            llama_memory_clear(memory, false);
        }

        // The anchor ends right after the first end-of-turn token (e.g. <|im_end|> of the system message).
        size_t anchor_end = 0;
        for (size_t i = 0; i < n; ++i) {
            if (llama_vocab_is_eog(vocab, tokens[i])) { anchor_end = i + 1; break; }
        }
        const bool anchor_stale = anchor_.tokens.size() != anchor_end || !starts_with(tokens, anchor_.tokens);
        if (anchor_end > done && anchor_end + 1 < n && anchor_end <= end && anchor_stale) {
            if (!decode(ctx, tokens, done, anchor_end, batch)) return false;
            splits_.push_back(anchor_end);
            done = anchor_end;
            save(ctx, anchor_, tokens, anchor_end);
        }
        if (end > done) {
            if (!decode(ctx, tokens, done, end, batch)) return false;
            splits_.push_back(end);
        }
        return true;
    }

    static bool starts_with(const std::vector<llama_token> &tokens, const std::vector<llama_token> &prefix) {
        if (prefix.empty() || prefix.size() > tokens.size()) return false;
        for (size_t i = 0; i < prefix.size(); ++i) if (tokens[i] != prefix[i]) return false;
        return true;
    }

    static bool decode(llama_context *ctx, const std::vector<llama_token> &tokens, size_t from, size_t to, int batch) {
        for (size_t offset = from; offset < to; offset += batch) {
            const int count = static_cast<int>(std::min(to - offset, static_cast<size_t>(batch)));
            llama_batch chunk = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + offset), count);
            if (llama_decode(ctx, chunk) != 0) return false;
        }
        return true;
    }

    void save(llama_context *ctx, Snapshot &snapshot, const std::vector<llama_token> &tokens, size_t count) {
        const size_t size = llama_state_seq_get_size(ctx, 0);
        snapshot.state.resize(size);
        if (llama_state_seq_get_data(ctx, snapshot.state.data(), size, 0) != size) {
            snapshot = Snapshot{};
            return;
        }
        snapshot.tokens.assign(tokens.begin(), tokens.begin() + static_cast<std::ptrdiff_t>(count));
        snapshot.splits = splits_;
    }
};
