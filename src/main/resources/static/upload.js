(() => {
  const endpoint = "/api/v1/classify/upload-csv";
  const llmStatusEndpoint = "/api/v1/classify/llm-status";

  const refs = {
    dropzone: document.getElementById("dropzone"),
    fileInput: document.getElementById("fileInput"),
    selectedFile: document.getElementById("selectedFile"),
    uploadBtn: document.getElementById("uploadBtn"),
    exportBtn: document.getElementById("exportBtn"),
    clearBtn: document.getElementById("clearBtn"),
    status: document.getElementById("status"),
    summarySection: document.getElementById("summarySection"),
    summaryFile: document.getElementById("summaryFile"),
    summaryRows: document.getElementById("summaryRows"),
    summaryConfidence: document.getElementById("summaryConfidence"),
    summaryState: document.getElementById("summaryState"),
    resultsSection: document.getElementById("resultsSection"),
    resultsGrid: document.getElementById("resultsGrid"),
    emptyState: document.getElementById("emptyState")
  };

  const state = {
    file: null,
    rows: [],
    uploading: false,
    llmJobId: null,
    llmPollTimer: null,
    llmPollStartedAt: null
  };

  function setStatus(message, kind = "") {
    refs.status.textContent = message;
    refs.status.className = ["status", kind].filter(Boolean).join(" ");
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function clearResults() {
    stopLlmPolling();
    state.rows = [];
    refs.summarySection.hidden = true;
    refs.resultsSection.hidden = true;
    refs.resultsGrid.innerHTML = "";
    refs.emptyState.hidden = false;
    updateExportState();
  }

  function resetFileSelection() {
    state.file = null;
    refs.fileInput.value = "";
    refs.selectedFile.textContent = "Keine Datei ausgewählt";
  }

  function setBusy(isBusy) {
    state.uploading = isBusy;
    refs.uploadBtn.disabled = isBusy;
    refs.clearBtn.disabled = isBusy;
    refs.fileInput.disabled = isBusy;
    refs.dropzone.setAttribute("aria-disabled", String(isBusy));
    updateExportState();
  }

  function updateExportState() {
    refs.exportBtn.disabled = state.uploading || state.rows.length === 0;
  }

  function isCsvFile(file) {
    const name = String(file?.name || "").toLowerCase();
    const type = String(file?.type || "").toLowerCase();
    return name.endsWith(".csv") || type === "text/csv" || type === "application/vnd.ms-excel";
  }

  function getItemsFromPayload(payload) {
    if (Array.isArray(payload)) {
      return payload;
    }

    if (payload && typeof payload === "object") {
      for (const key of ["results", "items", "rows", "data"]) {
        if (Array.isArray(payload[key])) {
          return payload[key];
        }
      }
    }

    return null;
  }

  function normalizeConfidenceLabel(value) {
    const label = String(value || "low").toLowerCase();
    if (label === "high" || label === "medium" || label === "low") return label;
    return "low";
  }

  function scoreFromRow(row) {
    const top = Number(row?.candidates?.[0]?.score);
    if (!Number.isNaN(top) && Number.isFinite(top)) {
      return Math.max(0, Math.min(100, top));
    }

    const confidence = normalizeConfidenceLabel(row?.confidence);
    if (confidence === "high") return 85;
    if (confidence === "medium") return 60;
    return 35;
  }

  function confidenceLabel(value) {
    const label = normalizeConfidenceLabel(value);
    if (label === "high") return "Hoch";
    if (label === "medium") return "Mittel";
    return "Niedrig";
  }

  function renderCandidate(candidate) {
    const score = Number(candidate?.score);
    const safeScore = Number.isFinite(score) ? score.toFixed(1) : "0.0";

    return `
      <article class="candidate">
        <p class="candidate-code">${escapeHtml(candidate?.code || "k. A.")}</p>
        <p class="candidate-label">${escapeHtml(candidate?.label || "Keine Bezeichnung")}</p>
        <p class="candidate-score">Confidence-Score: ${escapeHtml(safeScore)}%</p>
      </article>
    `;
  }

  function renderHints(row) {
    const hints = Array.isArray(row?.missingInformation) ? row.missingInformation : [];
    if (hints.length === 0) {
      return '<p class="no-hints">Keine weiteren Angaben erforderlich.</p>';
    }

    const items = hints.map((hint) => `<li>${escapeHtml(hint)}</li>`).join("");
    return `<ul class="hints">${items}</ul>`;
  }

  function normalizeLlmStatus(value) {
    const status = String(value || "").toLowerCase();
    if (status === "pending" || status === "completed" || status === "failed" || status === "skipped") {
      return status;
    }
    return "";
  }

  function llmConfidence(value) {
    const numeric = Number(value);
    if (Number.isFinite(numeric)) {
      return Math.max(0, Math.min(100, Math.round(numeric)));
    }
    return null;
  }

  function renderLlmClassification(row) {
    const llm = row?.llm;
    const llmStatus = normalizeLlmStatus(row?.llmStatus);
    if (llmStatus === "pending") {
      return `
        <div class="llm-block">
          <p class="llm-headline">KI-Klassifizierung wird verarbeitet ...</p>
          <p class="llm-loading"><span class="spinner" aria-hidden="true"></span> Bitte warten, die LLM-Antwort wird nachgeladen.</p>
        </div>
      `;
    }

    if (llmStatus === "failed") {
      return '<p class="llm-empty">LLM-Ergebnis konnte nicht erzeugt werden. Lokales Ergebnis bleibt bestehen.</p>';
    }

    if (llmStatus === "skipped") {
      return '<p class="llm-empty">LLM-Anreicherung ist deaktiviert oder nicht konfiguriert.</p>';
    }

    if (!llm || typeof llm !== "object") {
      return '<p class="llm-empty">Kein LLM-Ergebnis verfügbar.</p>';
    }

    const candidateHeadlines = Array.isArray(llm.candidateHeadlines) ? llm.candidateHeadlines : [];
    const candidatesHtml = candidateHeadlines.length > 0
      ? `<ul class="llm-candidates">${candidateHeadlines.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`
      : '<p class="llm-empty">Keine LLM-Kandidatenzeilen vorhanden.</p>';

    const confidence = llmConfidence(llm.confidencePercent);

    return `
      <div class="llm-block">
        <p class="llm-headline">${escapeHtml(llm.headline || "Ohne LLM-Headline")}</p>
        <div class="llm-meta">
          <span>Gewählte CN: <strong>${escapeHtml(llm.selectedCnCode || "k. A.")}</strong></span>
          <span>LLM-Confidence: <strong>${confidence === null ? "k. A." : `${confidence}%`}</strong></span>
        </div>
        <p class="llm-explanation">${escapeHtml(llm.explanation || "Keine Begründung vorhanden.")}</p>
        ${candidatesHtml}
      </div>
    `;
  }

  function renderRowCard(row) {
    const confidence = normalizeConfidenceLabel(row?.confidence);
    const score = scoreFromRow(row);
    const candidates = Array.isArray(row?.candidates) ? row.candidates.slice(0, 3) : [];

    const candidatesHtml = candidates.length > 0
      ? candidates.map(renderCandidate).join("")
      : '<article class="candidate"><p class="candidate-label">Keine KN-Kandidaten gefunden.</p></article>';

    return `
      <article class="result-card">
        <div class="card-top">
          <div class="field-grid">
            <div class="field">
              <p class="field-label">Materialnummer</p>
              <p class="field-value">${escapeHtml(row?.materialNumber || "")}</p>
            </div>
            <div class="field">
              <p class="field-label">Kurztext</p>
              <p class="field-value">${escapeHtml(row?.shortText || "")}</p>
            </div>
            <div class="field">
              <p class="field-label">Einkaufsbestelltext</p>
              <p class="field-value">${escapeHtml(row?.purchaseText || "")}</p>
            </div>
          </div>

          <aside class="confidence-box">
            <div class="confidence-row">
              <span>Confidence</span>
              <span class="confidence-label ${escapeHtml(confidence)}">${escapeHtml(confidenceLabel(confidence))}</span>
            </div>
            <div class="progress"><span style="width:${score.toFixed(1)}%"></span></div>
            <p class="candidate-score">Confidence-Score: ${score.toFixed(1)}%</p>
          </aside>
        </div>

        <section>
          <h3 class="section-title">Top-KN-Kandidaten</h3>
          <div class="candidates">${candidatesHtml}</div>
        </section>

        <section>
          <h3 class="section-title">LLM-Klassifizierung (Vergleich)</h3>
          ${renderLlmClassification(row)}
        </section>

        <section>
          <h3 class="section-title">Hinweise fur höhere Confidence</h3>
          ${renderHints(row)}
        </section>
      </article>
    `;
  }

  function updateSummary(fileName, rows, ok) {
    const avgConfidence = rows.length === 0
      ? 0
      : rows.reduce((sum, row) => sum + scoreFromRow(row), 0) / rows.length;

    refs.summarySection.hidden = false;
    refs.summaryFile.textContent = fileName || "(unbekannt)";
    refs.summaryRows.textContent = String(rows.length);
    refs.summaryConfidence.textContent = `${avgConfidence.toFixed(1)}%`;
    refs.summaryState.textContent = ok ? "Erfolgreich" : "Fehlgeschlagen";
  }

  function renderRows(rows) {
    state.rows = rows;
    refs.emptyState.hidden = true;
    refs.resultsSection.hidden = false;
    refs.resultsGrid.innerHTML = rows.map((row) => renderRowCard(row)).join("");
    updateExportState();
  }

  function readErrorMessage(errorPayload, fallbackMessage) {
    if (!errorPayload || typeof errorPayload !== "object") {
      return fallbackMessage;
    }

    const message = [errorPayload.message, errorPayload.details]
      .filter((part) => typeof part === "string" && part.trim().length > 0)
      .join(" ");

    return message || fallbackMessage;
  }

  function stopLlmPolling() {
    if (state.llmPollTimer) {
      clearTimeout(state.llmPollTimer);
      state.llmPollTimer = null;
    }
    state.llmJobId = null;
    state.llmPollStartedAt = null;
  }

  async function pollLlmResults() {
    const jobId = state.llmJobId;
    if (!jobId) {
      return;
    }
    if (state.llmPollStartedAt && Date.now() - state.llmPollStartedAt > 10 * 60 * 1000) {
      stopLlmPolling();
      setStatus("KI-Anreicherung hat das Zeitlimit überschritten. Bitte erneut versuchen oder Batchgröße reduzieren.", "error");
      return;
    }

    try {
      const response = await fetch(`${llmStatusEndpoint}/${encodeURIComponent(jobId)}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        cache: "no-store"
      });

      const text = await response.text();
      const payload = text.trim().length > 0 ? JSON.parse(text) : null;
      if (!response.ok) {
        throw new Error(readErrorMessage(payload, `LLM-Statusabfrage fehlgeschlagen (HTTP ${response.status}).`));
      }

      const rows = getItemsFromPayload(payload);
      if (rows) {
        renderRows(rows);
        updateSummary(state.file?.name || "(unbekannt)", rows, true);
      }

      const jobStatus = String(payload?.llmJobStatus || "").toLowerCase();
      if (jobStatus === "completed") {
        stopLlmPolling();
        setStatus(`Upload erfolgreich. ${state.rows.length} Ergebnisse inklusive KI-Ausgabe geladen.`, "ok");
        return;
      }

      if (jobStatus === "failed") {
        stopLlmPolling();
        setStatus("Lokale Klassifizierung erfolgreich, aber KI-Anreicherung ist fehlgeschlagen.", "error");
        return;
      }

      setStatus(`Lokale Klassifizierung fertig (${state.rows.length}). KI-Ergebnisse werden nachgeladen ...`, "loading");
      state.llmPollTimer = setTimeout(pollLlmResults, 1500);
    } catch (error) {
      stopLlmPolling();
      const message = error instanceof Error ? error.message : String(error);
      setStatus(`Lokale Klassifizierung fertig, KI-Status konnte nicht aktualisiert werden: ${message}`, "error");
    }
  }

  function selectFile(file) {
    if (!file) {
      return;
    }

    state.file = file;
    refs.selectedFile.textContent = `${file.name} (${file.size} bytes)`;
  }

  function csvCell(value) {
    const text = String(value ?? "").replace(/\r?\n/g, " ");
    return `"${text.replace(/"/g, "\"\"")}"`;
  }

  function formatPercent(value) {
    const numeric = Number(value);
    if (Number.isFinite(numeric)) {
      return numeric.toFixed(1);
    }
    return "0.0";
  }

  function exportCsv() {
    if (state.uploading || state.rows.length === 0) {
      setStatus("Keine Ergebnisse verfuegbar. Fuehre erst einen Upload aus.", "error");
      return;
    }

    const headers = [
      "Materialnummer",
      "Kurztext",
      "Einkaufsbestelltext",
      "Confidence-Level",
      "Confidence-Score",
      "Kandidat 1 Code",
      "Kandidat 1 Bezeichnung",
      "Kandidat 1 Score",
      "Kandidat 2 Code",
      "Kandidat 2 Bezeichnung",
      "Kandidat 2 Score",
      "Kandidat 3 Code",
      "Kandidat 3 Bezeichnung",
      "Kandidat 3 Score",
      "Hinweise"
    ];

    const lines = [headers.map(csvCell).join(";")];

    for (const row of state.rows) {
      const candidates = Array.isArray(row?.candidates) ? row.candidates : [];
      const hints = Array.isArray(row?.missingInformation) ? row.missingInformation.join(" | ") : "";

      lines.push([
        row?.materialNumber || "",
        row?.shortText || "",
        row?.purchaseText || "",
        confidenceLabel(row?.confidence),
        scoreFromRow(row).toFixed(1),
        candidates[0]?.code || "",
        candidates[0]?.label || "",
        formatPercent(candidates[0]?.score),
        candidates[1]?.code || "",
        candidates[1]?.label || "",
        formatPercent(candidates[1]?.score),
        candidates[2]?.code || "",
        candidates[2]?.label || "",
        formatPercent(candidates[2]?.score),
        hints
      ].map(csvCell).join(";"));
    }

    const csvContent = `\uFEFF${lines.join("\n")}`;
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);

    const date = new Date().toISOString().slice(0, 10);
    const base = String(state.file?.name || "zollpilot_upload").replace(/\.[^/.]+$/, "");
    const safeBase = base.replace(/[^a-z0-9_-]+/gi, "_").replace(/_+/g, "_").replace(/^_+|_+$/g, "");
    const fileName = `${safeBase || "zollpilot_upload"}_kn_hs_${date}.csv`;

    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);

    setStatus(`CSV exportiert: ${fileName}`, "ok");
  }

  function handleChosenFile(file) {
    if (!file) {
      setStatus("Keine Datei ausgewählt.", "error");
      return false;
    }

    if (!isCsvFile(file)) {
      setStatus("Bitte wählen Sie eine CSV-Datei (*.csv) aus.", "error");
      return false;
    }

    if (file.size === 0) {
      setStatus("Die ausgewählte Datei ist leer.", "error");
      return false;
    }

    selectFile(file);
    setStatus("Datei ausgewählt. Bereit zum Hochladen.");
    return true;
  }

  async function uploadCsv() {
    if (state.uploading) {
      return;
    }

    if (!state.file) {
      setStatus("Wählen Sie vor dem Hochladen eine CSV-Datei aus.", "error");
      return;
    }

    if (!isCsvFile(state.file)) {
      setStatus("Es werden nur CSV-Dateien unterstützt.", "error");
      return;
    }

    if (state.file.size === 0) {
      setStatus("Die ausgewählte Datei ist leer.", "error");
      return;
    }

    stopLlmPolling();
    setBusy(true);
    setStatus(`${state.file.name} wird hochgeladen ...`, "loading");

    try {
      const form = new FormData();
      form.append("file", state.file);

      const response = await fetch(endpoint, {
        method: "POST",
        body: form,
        headers: {
          Accept: "application/json"
        }
      });

      const text = await response.text();
      let payload = null;

      if (text.trim().length > 0) {
        try {
          payload = JSON.parse(text);
        } catch (_) {
          throw new Error("Der Server hat fehlerhaftes JSON zuruckgegeben.");
        }
      }

      if (!response.ok) {
        throw new Error(readErrorMessage(payload, `Upload failed with HTTP ${response.status}.`));
      }

      if (payload == null) {
        throw new Error("Server returned an empty response.");
      }

      const rows = getItemsFromPayload(payload);
      if (!rows) {
        throw new Error("Unexpected response structure from server.");
      }

      renderRows(rows);
      updateSummary(state.file?.name || "(unbekannt)", rows, true);
      const llmJobId = typeof payload?.llmJobId === "string" ? payload.llmJobId : null;
      const llmJobStatus = String(payload?.llmJobStatus || "").toLowerCase();

      if (llmJobId && llmJobStatus === "processing") {
        state.llmJobId = llmJobId;
        state.llmPollStartedAt = Date.now();
        setStatus(`Lokale Klassifizierung fertig (${rows.length}). KI-Ergebnisse werden nachgeladen ...`, "loading");
        state.llmPollTimer = setTimeout(pollLlmResults, 1200);
      } else {
        setStatus(`Upload erfolgreich. ${rows.length} Ergebnisse erhalten.`, "ok");
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      stopLlmPolling();
      state.rows = [];
      updateSummary(state.file?.name || "(unbekannt)", [], false);
      refs.resultsGrid.innerHTML = "";
      refs.emptyState.hidden = false;
      refs.resultsSection.hidden = true;
      updateExportState();
      setStatus(message, "error");
    } finally {
      setBusy(false);
    }
  }

  function clearAll() {
    if (state.uploading) {
      return;
    }

    stopLlmPolling();
    resetFileSelection();
    clearResults();
    setStatus("Auswahl und Ergebnisse wurden entfernt.");
  }

  refs.dropzone.addEventListener("click", () => {
    if (!state.uploading) {
      refs.fileInput.click();
    }
  });

  refs.dropzone.addEventListener("keydown", (event) => {
    if (state.uploading) {
      return;
    }

    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      refs.fileInput.click();
    }
  });

  refs.fileInput.addEventListener("change", (event) => {
    const file = event.target.files && event.target.files[0] ? event.target.files[0] : null;
    if (file) {
      handleChosenFile(file);
    }
  });

  ["dragenter", "dragover"].forEach((eventName) => {
    refs.dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      if (!state.uploading) {
        refs.dropzone.classList.add("is-dragging");
      }
    });
  });

  ["dragleave", "drop"].forEach((eventName) => {
    refs.dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      refs.dropzone.classList.remove("is-dragging");
    });
  });

  refs.dropzone.addEventListener("drop", (event) => {
    if (state.uploading) {
      return;
    }

    const file = event.dataTransfer?.files?.[0] || null;
    if (file) {
      handleChosenFile(file);
    }
  });

  refs.uploadBtn.addEventListener("click", uploadCsv);
  refs.exportBtn.addEventListener("click", exportCsv);
  refs.clearBtn.addEventListener("click", clearAll);

  clearResults();
})();
