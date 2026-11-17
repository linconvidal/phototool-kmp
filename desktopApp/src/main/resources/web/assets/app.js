(() => {
  "use strict";

  const state = {
    csrf: "",
    writeEnabled: false,
    mutationsAllowed: false,
    galleryRequest: 0,
    detailRequest: 0,
    statusRequest: 0,
    facetsRequest: 0,
    page: null,
    detail: null,
    facets: null,
    searchTimer: null,
    noticeTimer: null,
    syncTimer: null,
  };

  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const gallery = $("#gallery");
  const detailDialog = $("#detail-dialog");
  const filterInputs = $$('[data-param]');
  const queryNames = ["q", "keyword", "fromDate", "toDate", "flag", "camera", "lens", "minimumStars", "gps", "folder", "sort", "page"];

  class ApiFailure extends Error {
    constructor(status, payload) {
      super(payload?.message || `HTTP ${status}`);
      this.status = status;
      this.payload = payload || {};
    }
  }

  async function api(url, options = {}) {
    const response = await fetch(url, {
      credentials: "same-origin",
      headers: { Accept: "application/json", ...(options.headers || {}) },
      ...options,
    });
    if (response.status === 304) return null;
    const type = response.headers.get("content-type") || "";
    const payload = type.includes("application/json") ? await response.json() : null;
    if (!response.ok) throw new ApiFailure(response.status, payload);
    return payload;
  }

  function urlState() {
    return new URL(window.location.href);
  }

  function apiQuery(includePage = true) {
    const source = urlState().searchParams;
    const output = new URLSearchParams();
    queryNames.forEach((name) => {
      if (!includePage && name === "page") return;
      const value = source.get(name);
      if (value) output.set(name, value);
    });
    output.set("pageSize", "24");
    return output;
  }

  function replaceParameters(changes, push = false) {
    const url = urlState();
    Object.entries(changes).forEach(([name, value]) => {
      if (value === null || value === "" || value === undefined) url.searchParams.delete(name);
      else url.searchParams.set(name, String(value));
    });
    const method = push ? "pushState" : "replaceState";
    history[method]({}, "", url);
  }

  function option(value, label) {
    const node = document.createElement("option");
    node.value = value;
    node.textContent = label;
    return node;
  }

  function fillSelect(selector, values) {
    const select = $(selector);
    const current = urlState().searchParams.get(select.dataset.param) || "";
    while (select.options.length > 1) select.remove(1);
    values.forEach((item) => select.append(option(item.value, `${item.label} · ${item.count}`)));
    if (current && !values.some((item) => item.value === current)) {
      const label = select.dataset.param === "folder" ? "Pasta ativa fora da lista" : `${current} · filtro ativo`;
      select.append(option(current, label));
    }
    select.value = current;
  }

  function applyUrlToControls() {
    const params = urlState().searchParams;
    $("#search-input").value = params.get("q") || "";
    filterInputs.forEach((input) => {
      const fallback = input.dataset.param === "sort" ? "CAPTURE_TIME" : "";
      input.value = params.get(input.dataset.param) || fallback;
    });
    updateFilterCount();
  }

  function updateFilterCount() {
    const params = urlState().searchParams;
    const active = ["keyword", "fromDate", "toDate", "flag", "camera", "lens", "minimumStars", "gps", "folder"]
      .filter((name) => params.has(name)).length;
    const badge = $("#filter-count");
    badge.textContent = String(active);
    badge.hidden = active === 0;
    $("#filters-button").classList.toggle("selected", active > 0);
  }

  async function loadSession() {
    const session = await api("/api/v1/session");
    state.csrf = session.csrfToken;
    state.writeEnabled = session.writeEnabled;
  }

  async function loadStatus() {
    const request = ++state.statusRequest;
    const status = await api("/api/v1/status");
    if (request !== state.statusRequest) return status;
    const sync = status.sync;
    const projection = status.projectionState === "DEGRADED" ? " · projeção exige reparo" : "";
    state.mutationsAllowed = state.writeEnabled && status.projectionState === "READY" && !sync.running;
    $("#snapshot-status").textContent = `${status.photoCount} fotos · ${sync.running ? sync.phase.toLowerCase() : "snapshot confirmado"}${projection}`;
    if (state.detail) renderEditorial();
    const button = $("#sync-button");
    button.disabled = sync.running;
    button.setAttribute("aria-busy", String(sync.running));
    button.title = sync.running ? sync.message : "Sincronizar biblioteca";
    if (sync.running && !state.syncTimer) {
      state.syncTimer = window.setInterval(async () => {
        try {
          const request = ++state.statusRequest;
          const current = await api("/api/v1/status");
          if (request !== state.statusRequest) return;
          state.mutationsAllowed = state.writeEnabled && current.projectionState === "READY" && !current.sync.running;
          if (state.detail) renderEditorial();
          if (!current.sync.running) {
            clearInterval(state.syncTimer);
            state.syncTimer = null;
            const openPhotoId = state.detail?.id || null;
            await Promise.all([loadStatus(), loadFacets(), loadGallery()]);
            if (openPhotoId && urlState().searchParams.get("photo") === openPhotoId) await openDetail(openPhotoId, false);
            notice(current.sync.message);
          } else {
            $("#snapshot-status").textContent = `${current.sync.photos}/${current.sync.totalPhotos ?? "?"} · ${current.sync.phase.toLowerCase()}`;
          }
        } catch (error) {
          clearInterval(state.syncTimer);
          state.syncTimer = null;
          showFailure(error);
        }
      }, 750);
    }
    return status;
  }

  async function loadFacets() {
    const request = ++state.facetsRequest;
    const facets = await api("/api/v1/facets");
    if (request !== state.facetsRequest) return;
    state.facets = facets;
    fillSelect("#filter-keyword", state.facets.keywords);
    fillSelect("#filter-camera", state.facets.cameras);
    fillSelect("#filter-lens", state.facets.lenses);
    fillSelect("#filter-folder", state.facets.folders);
    applyUrlToControls();
    if (state.facets.truncated) notice("Filtros limitados às primeiras 500 opções de cada categoria");
  }

  function badge(text, className = "") {
    const node = document.createElement("span");
    node.className = `badge ${className}`.trim();
    node.textContent = text;
    return node;
  }

  function photoCard(photo) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "photo-card";
    card.dataset.photoId = photo.id;
    card.style.setProperty("--ratio", String(Math.max(.45, Math.min(2.4, photo.aspectRatio || 1.5))));
    card.setAttribute("aria-label", `Abrir ${photo.displayName}`);
    card.setAttribute("aria-current", String(state.detail?.id === photo.id));

    const image = document.createElement("img");
    image.src = photo.previewUrl;
    image.alt = photo.displayName;
    image.loading = "eager";
    image.decoding = "async";
    image.addEventListener("error", () => {
      image.alt = `Prévia indisponível para ${photo.displayName}`;
      card.classList.add("preview-error");
    });
    card.append(image);

    const badges = document.createElement("span");
    badges.className = "badges";
    if (photo.editorial.flag === "PICK") badges.append(badge("✓", "pick"));
    if (photo.editorial.flag === "REJECT") badges.append(badge("×", "reject"));
    if (photo.editorial.rating > 0) badges.append(badge(`${photo.editorial.rating}★`));
    card.append(badges);

    const proof = document.createElement("span");
    proof.className = "proof";
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = photo.displayName;
    const format = document.createElement("span");
    format.className = "format";
    format.textContent = photo.format;
    proof.append(name, format);
    card.append(proof);
    card.addEventListener("click", () => openDetail(photo.id, true));
    return card;
  }

  async function loadGallery() {
    const request = ++state.galleryRequest;
    gallery.setAttribute("aria-busy", "true");
    try {
      const page = await api(`/api/v1/photos?${apiQuery(true)}`);
      if (request !== state.galleryRequest) return;
      state.page = page;
      gallery.replaceChildren(...page.items.map(photoCard));
      $("#result-count").textContent = `${page.total} ${page.total === 1 ? "fotografia" : "fotografias"}`;
      $("#page-status").textContent = page.totalPages ? `Página ${page.page} de ${page.totalPages}` : "Sem páginas";
      $("#previous-page").disabled = page.page <= 1;
      $("#next-page").disabled = page.totalPages === 0 || page.page >= page.totalPages;
      $("#empty-state").hidden = page.items.length > 0;
    } catch (error) {
      if (request !== state.galleryRequest) return;
      gallery.replaceChildren();
      $("#result-count").textContent = "Não foi possível carregar este recorte";
      showFailure(error);
    } finally {
      if (request === state.galleryRequest) gallery.setAttribute("aria-busy", "false");
    }
  }

  async function openDetail(photoId, push = false) {
    const request = ++state.detailRequest;
    replaceParameters({ photo: photoId }, push);
    try {
      const detail = await api(`/api/v1/photos/${encodeURIComponent(photoId)}?${apiQuery(false)}`);
      if (request !== state.detailRequest || urlState().searchParams.get("photo") !== photoId) return;
      state.detail = detail;
      renderDetail();
      if (!detailDialog.open) detailDialog.showModal();
      $$(".photo-card").forEach((card) => card.setAttribute("aria-current", String(card.dataset.photoId === detail.id)));
    } catch (error) {
      if (request !== state.detailRequest || urlState().searchParams.get("photo") !== photoId) return;
      closeDetail(false);
      showFailure(error);
    }
  }

  function closeDetail(push = true) {
    state.detailRequest++;
    if (detailDialog.open) detailDialog.close();
    state.detail = null;
    replaceParameters({ photo: null }, push);
    $$(".photo-card").forEach((card) => card.setAttribute("aria-current", "false"));
  }

  function renderDetail() {
    const detail = state.detail;
    if (!detail) return;
    $("#detail-title").textContent = detail.displayName;
    $("#detail-format").textContent = detail.format;
    const image = $("#detail-image");
    image.src = detail.previewUrl;
    image.alt = detail.displayName;
    $("#previous-photo").disabled = !detail.previousId;
    $("#next-photo").disabled = !detail.nextId;
    renderEditorial();
    renderMetadata(detail.metadata);
  }

  function setSelected(button, selected) {
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-pressed", String(selected));
  }

  function renderEditorial() {
    const detail = state.detail;
    const editorial = detail.editorial;
    $$("[data-flag]").forEach((button) => setSelected(button, button.dataset.flag === editorial.flag));
    $$("[data-rating]").forEach((button) => {
      const rating = Number(button.dataset.rating);
      setSelected(button, rating > 0 && rating <= editorial.rating);
    });
    $$("[data-label]").forEach((button) => setSelected(button, (button.dataset.label || null) === editorial.label));
    const list = $("#keyword-list");
    list.replaceChildren(...editorial.keywords.map((keyword) => {
      const chip = document.createElement("span");
      chip.className = "keyword-chip";
      const text = document.createElement("span");
      text.textContent = keyword;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = "×";
      remove.setAttribute("aria-label", `Remover keyword ${keyword}`);
      remove.addEventListener("click", () => mutate("keywords", { keyword }, "DELETE"));
      chip.append(text, remove);
      return chip;
    }));
    const enabled = state.mutationsAllowed && detail.writable;
    $$("#flag-controls button, #rating-controls button, #label-controls button, #keyword-form input, #keyword-form button, #keyword-list button")
      .forEach((control) => { control.disabled = !enabled; });
    $("#write-state").textContent = enabled ? "XMP editável" : "Somente leitura";
  }

  function metadataValue(value, suffix = "") {
    if (value === null || value === undefined || value === "") return "—";
    return `${value}${suffix}`;
  }

  function renderMetadata(metadata) {
    const rows = [
      ["Captura", metadata.capturedAt], ["Câmera", metadata.camera], ["Fabricante", metadata.cameraMake], ["Modelo", metadata.cameraModel],
      ["Lente", metadata.lens], ["Distância focal", metadata.focalLength, " mm"], ["Abertura", metadata.aperture ? `f/${metadata.aperture}` : null],
      ["Exposição", metadata.exposureSeconds, " s"], ["ISO", metadata.iso], ["Dimensões", metadata.width && metadata.height ? `${metadata.width} × ${metadata.height}` : null],
      ["Latitude", metadata.latitude], ["Longitude", metadata.longitude], ["Leitura", metadata.status], ["Diagnóstico", metadata.errorCode],
    ];
    const list = $("#metadata-list");
    const nodes = [];
    rows.forEach(([label, value, suffix]) => {
      const term = document.createElement("dt");
      term.textContent = label;
      const description = document.createElement("dd");
      description.textContent = metadataValue(value, suffix || "");
      nodes.push(term, description);
    });
    list.replaceChildren(...nodes);
  }

  function setMutationPending(pending) {
    $$("#flag-controls button, #rating-controls button, #label-controls button, #keyword-form input, #keyword-form button, #keyword-list button")
      .forEach((control) => { control.disabled = pending; });
    $("#write-state").textContent = pending ? "Salvando no XMP…" : "XMP editável";
  }

  async function mutate(endpoint, values, method = "POST") {
    if (!state.detail) return false;
    const photoId = state.detail.id;
    const detailRequest = state.detailRequest;
    const revision = state.detail.editorial.revision;
    setMutationPending(true);
    try {
      const result = await api(`/api/v1/photos/${encodeURIComponent(photoId)}/${endpoint}`, {
        method,
        headers: { "Content-Type": "application/json", "X-PhotoTool-CSRF": state.csrf },
        body: JSON.stringify({ ...values, expectedEditorialRevision: revision }),
      });
      if (state.detail?.id === photoId && state.detailRequest === detailRequest) {
        state.detail.editorial = result.editorial;
        renderEditorial();
        notice("XMP salvo e projeção confirmada");
      }
      await loadGallery();
      return true;
    } catch (error) {
      if (error.payload?.editorial && state.detail?.id === photoId && state.detailRequest === detailRequest) {
        state.detail.editorial = error.payload.editorial;
        renderEditorial();
      }
      if (error.payload?.xmpPersisted === true && error.payload?.projectionUpdated === false) {
        state.mutationsAllowed = false;
        renderEditorial();
      }
      await loadStatus().catch(() => {});
      showFailure(error);
      return false;
    } finally {
      if (state.detail) renderEditorial();
    }
  }

  function notice(message, error = false) {
    const node = $("#notice");
    clearTimeout(state.noticeTimer);
    node.textContent = message;
    node.classList.toggle("error", error);
    node.hidden = false;
    state.noticeTimer = window.setTimeout(() => { node.hidden = true; }, 4500);
  }

  function showFailure(error) {
    const message = error instanceof ApiFailure ? `${error.payload?.code || "erro"}: ${error.message}` : (error.message || "Falha inesperada");
    notice(message, true);
  }

  async function synchronize() {
    try {
      const command = await api("/api/v1/sync", {
        method: "POST",
        headers: { "X-PhotoTool-CSRF": state.csrf, Origin: window.location.origin },
      });
      notice(command.started ? "Sincronização explícita iniciada" : "Sincronização já estava em andamento");
      await loadStatus();
    } catch (error) { showFailure(error); }
  }

  function clearQuery() {
    const changes = Object.fromEntries(queryNames.map((name) => [name, null]));
    replaceParameters(changes);
    applyUrlToControls();
    loadGallery();
  }

  function bindEvents() {
    $("#filters-button").addEventListener("click", () => {
      const panel = $("#filters-panel");
      panel.hidden = !panel.hidden;
      $("#filters-button").setAttribute("aria-expanded", String(!panel.hidden));
    });
    filterInputs.forEach((input) => input.addEventListener("change", () => {
      const value = (input.dataset.param === "sort" && input.value === "CAPTURE_TIME") ? null : input.value;
      replaceParameters({ [input.dataset.param]: value, page: null });
      updateFilterCount();
      loadGallery();
    }));
    $("#search-input").addEventListener("input", (event) => {
      clearTimeout(state.searchTimer);
      state.searchTimer = window.setTimeout(() => {
        replaceParameters({ q: event.target.value.trim() || null, page: null });
        loadGallery();
      }, 220);
    });
    $("#search-input").addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        clearTimeout(state.searchTimer);
        replaceParameters({ q: event.currentTarget.value.trim() || null, page: null });
        loadGallery();
      }
    });
    $("#clear-filters").addEventListener("click", clearQuery);
    $("#empty-clear").addEventListener("click", clearQuery);
    $("#previous-page").addEventListener("click", () => { replaceParameters({ page: Math.max(1, state.page.page - 1) }, true); loadGallery(); scrollTo({ top: 0 }); });
    $("#next-page").addEventListener("click", () => { replaceParameters({ page: state.page.page + 1 }, true); loadGallery(); scrollTo({ top: 0 }); });
    $("#sync-button").addEventListener("click", synchronize);
    $("#close-detail").addEventListener("click", () => closeDetail(true));
    $("#previous-photo").addEventListener("click", () => state.detail?.previousId && openDetail(state.detail.previousId, true));
    $("#next-photo").addEventListener("click", () => state.detail?.nextId && openDetail(state.detail.nextId, true));
    $$("[data-flag]").forEach((button) => button.addEventListener("click", () => mutate("flag", { value: button.dataset.flag })));
    $$("[data-rating]").forEach((button) => button.addEventListener("click", () => mutate("rating", { value: Number(button.dataset.rating) })));
    $$("[data-label]").forEach((button) => button.addEventListener("click", () => mutate("label", { value: button.dataset.label || null })));
    $("#keyword-form").addEventListener("submit", (event) => {
      event.preventDefault();
      const input = $("#keyword-input");
      if (input.value.trim()) mutate("keywords", { keyword: input.value }).then((saved) => { if (saved) input.value = ""; });
    });
    detailDialog.addEventListener("cancel", (event) => { event.preventDefault(); closeDetail(true); });
    window.addEventListener("keydown", (event) => {
      if (!state.detail || ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement?.tagName)) return;
      if (event.key === "ArrowLeft" && state.detail.previousId) openDetail(state.detail.previousId, true);
      if (event.key === "ArrowRight" && state.detail.nextId) openDetail(state.detail.nextId, true);
    });
    window.addEventListener("popstate", async () => {
      applyUrlToControls();
      await loadGallery();
      const photo = urlState().searchParams.get("photo");
      if (photo) openDetail(photo, false);
      else if (detailDialog.open) closeDetail(false);
    });
  }

  async function start() {
    bindEvents();
    applyUrlToControls();
    try {
      await loadSession();
      await Promise.all([loadStatus(), loadFacets(), loadGallery()]);
      const photo = urlState().searchParams.get("photo");
      if (photo) await openDetail(photo, false);
    } catch (error) { showFailure(error); }
  }

  start();
})();
