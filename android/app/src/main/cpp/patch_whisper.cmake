# Patch a src/whisper.cpp, applicata da FetchContent dopo il download (PATCH_COMMAND, nella
# cartella dei sorgenti di whisper.cpp). Le modifiche sono due.
#
# La prima rende immediato l'abort_callback. Encoder e decoder calcolano i grafi con
# ggml_graph_compute_helper nella variante con lo scheduler, che non passa l'abort_callback al
# backend: la richiesta di interruzione si legge solo alla fine di ogni encode, cioè dopo
# un'intera finestra di 30 s di audio. La patch aggiunge alla variante i parametri dell'abort,
# li imposta su ogni backend che lo supporta (ggml_backend_set_abort_callback, che il backend
# CPU controlla dopo ogni nodo del grafo) e li fa passare dalle chiamate di encoder e decoder.
# Le altre chiamate lasciano i valori predefiniti nulli, che tolgono un abort rimasto impostato
# su un backend riusato.
#
# La seconda riguarda la lingua. whisper_full imposta la finestra dell'encoder (audio_ctx) solo
# dopo il rilevamento della lingua, che quindi codifica con la finestra lasciata dalla chiamata
# precedente sullo stesso stato. La patch la imposta prima del rilevamento: lingua e ciclo
# principale codificano con la stessa finestra, condizione necessaria anche per il riuso
# descritto sotto.
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

# Sostituisce tutte le occorrenze di old, che devono essere esattamente count.
function(replace_n count old new)
    string(LENGTH "${src}" len_src)
    string(LENGTH "${old}" len_old)
    string(REPLACE "${old}" "" without "${src}")
    string(LENGTH "${without}" len_without)
    math(EXPR found "(${len_src} - ${len_without}) / ${len_old}")
    if(NOT found EQUAL count)
        message(FATAL_ERROR "patch_whisper: ${found} occorrenze invece di ${count} in ${file}:\n${old}")
    endif()
    string(REPLACE "${old}" "${new}" out "${src}")
    set(src "${out}" PARENT_SCOPE)
endfunction()

function(replace_once old new)
    replace_n(1 "${old}" "${new}")
    set(src "${src}" PARENT_SCOPE)
endfunction()

replace_once(
"static bool ggml_graph_compute_helper(
      ggml_backend_sched_t   sched,
        struct ggml_cgraph * graph,
                       int   n_threads,
                      bool   sched_reset = true) {
    for (int i = 0; i < ggml_backend_sched_get_n_backends(sched); ++i) {
        ggml_backend_t backend = ggml_backend_sched_get_backend(sched, i);
        ggml_backend_dev_t dev = ggml_backend_get_device(backend);
        ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
"
"static bool ggml_graph_compute_helper(
      ggml_backend_sched_t   sched,
        struct ggml_cgraph * graph,
                       int   n_threads,
                      bool   sched_reset = true,
         ggml_abort_callback   abort_callback = nullptr,
                        void * abort_callback_data = nullptr) {
    for (int i = 0; i < ggml_backend_sched_get_n_backends(sched); ++i) {
        ggml_backend_t backend = ggml_backend_sched_get_backend(sched, i);
        ggml_backend_dev_t dev = ggml_backend_get_device(backend);
        ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;

        auto * fn_set_abort = (ggml_backend_set_abort_callback_t) ggml_backend_reg_get_proc_address(reg, \"ggml_backend_set_abort_callback\");
        if (fn_set_abort) {
            fn_set_abort(backend, abort_callback, abort_callback_data);
        }
")

# Le quattro chiamate stanno in whisper_encode_internal (tre) e whisper_decode_internal (una),
# che ricevono abort_callback e abort_callback_data come parametri.
replace_n(4
"ggml_graph_compute_helper(sched, gf, n_threads))"
"ggml_graph_compute_helper(sched, gf, n_threads, true, abort_callback, abort_callback_data))")

replace_once(
"    // auto-detect language if not specified
    if (params.language == nullptr"
"    int ${marker} = -1;

    // language detection encodes with the same audio context as the main loop
    state->exp_n_audio_ctx = params.audio_ctx;

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
