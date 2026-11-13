# Patch a src/whisper.cpp, applicata da FetchContent dopo il download (PATCH_COMMAND, nella
# cartella dei sorgenti di whisper.cpp).
#
# Con lingua "auto", whisper_full esegue l'encoder sulla prima finestra per rilevare la lingua e
# poi lo riesegue sulla stessa finestra nel ciclo principale. La patch ricorda che la finestra
# all'offset 0 è già codificata e salta il secondo encode. Il riuso è corretto perché la
# decodifica del rilevamento non modifica embd_enc né la cache cross, e il ciclo principale
# azzera la cache del decoder (whisper_kv_cache_clear) prima di decodificare.
#
# Ogni sostituzione deve trovare il testo atteso: se whisper.cpp cambia, la configurazione
# fallisce invece di produrre un binario senza patch. Una seconda esecuzione non fa nulla.

set(file "src/whisper.cpp")
file(READ "${file}" src)

set(marker "whisperwa_lang_encoded_seek")
string(FIND "${src}" "${marker}" already)
if(NOT already EQUAL -1)
    return()
endif()

function(replace_once old new)
    string(FIND "${src}" "${old}" pos)
    if(pos EQUAL -1)
        message(FATAL_ERROR "patch_whisper: testo non trovato in ${file}:\n${old}")
    endif()
    string(REPLACE "${old}" "${new}" out "${src}")
    set(src "${out}" PARENT_SCOPE)
endfunction()

replace_once(
"    // auto-detect language if not specified
    if (params.language == nullptr"
"    int ${marker} = -1;

    // auto-detect language if not specified
    if (params.language == nullptr")

replace_once(
"        state->lang_id = lang_id;
        params.language = whisper_lang_str(lang_id);
"
"        state->lang_id = lang_id;
        params.language = whisper_lang_str(lang_id);
        ${marker} = 0;
")

replace_once(
"        // encode audio features starting at offset seek
        if (!whisper_encode_internal(*ctx, *state, seek, params.n_threads, params.abort_callback, params.abort_callback_user_data)) {"
"        // encode audio features starting at offset seek, unless language detection already did
        if (seek == ${marker}) {
            ${marker} = -1;
        } else if (!whisper_encode_internal(*ctx, *state, seek, params.n_threads, params.abort_callback, params.abort_callback_user_data)) {")

file(WRITE "${file}" "${src}")
message(STATUS "patch_whisper: applicata a ${file}")
