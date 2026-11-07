(() => {
  const base = "/_objectstore/admin";
  const pages = new Set(["overview", "buckets", "bucket", "object", "versions", "credentials", "performance", "diagnostics", "health", "audit", "jobs", "cluster", "settings"]);
  let pending = null;
  let loadingTimer = null;
  let performanceScript = null;
  const defaults = { theme: "deep-dark", brand: "ObjectStore admin", icon: base + "/logo.png" };
  const themes = ["auto", "light", "dark", "deep-dark"];
  let branding = { name: defaults.brand, icon: defaults.icon, defaultTheme: defaults.theme };

  function saved(key, fallback) {
    try { return localStorage.getItem("objectstore-admin-" + key) || fallback; }
    catch { return fallback; }
  }

  function save(key, value) {
    try { localStorage.setItem("objectstore-admin-" + key, value); return true; }
    catch { return false; }
  }

  function clear(key) {
    try { localStorage.removeItem("objectstore-admin-" + key); return true; }
    catch { return false; }
  }

  function preferences() {
    const stored = saved("theme", "");
    const personalTheme = themes.includes(stored) ? stored : "";
    return { personalTheme, theme: personalTheme || branding.defaultTheme };
  }

  function applyPreferences() {
    const { theme, personalTheme } = preferences();
    const brand = branding.name;
    const icon = branding.icon;
    document.body.dataset.adminTheme = theme === "auto" ?
      (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark") : theme;
    for (const widget of document.querySelectorAll("biscuit-sidebarview, biscuit-navbarview, " +
      "biscuit-footerview, biscuit-cardview, biscuit-badgeview, biscuit-chartview, " +
      "biscuit-activity-chart, biscuit-tablayout, biscuit-formwizardview, " +
      "biscuit-pageloaderview, biscuit-loadingindicator, biscuit-skeletonview, biscuit-paginationview, " +
      "biscuit-codeview, biscuit-dialogview, biscuit-accordionview, biscuit-toastview"))
      widget.setAttribute("theme", theme);
    document.querySelector("biscuit-navbarview")?.setAttribute("brand", brand);
    document.querySelector("biscuit-footerview")?.setAttribute("brand", brand);
    const sidebarName = document.querySelector(".sidebar-brand strong");
    if (sidebarName) sidebarName.textContent = brand;
    const loginName = document.querySelector(".login-brand strong");
    if (loginName) loginName.textContent = brand;
    const sidebarIcon = document.querySelector(".sidebar-brand .mark img");
    if (sidebarIcon) sidebarIcon.src = icon;
    const loginIcon = document.querySelector(".login-brand .mark img");
    if (loginIcon) loginIcon.src = icon;
    const themeInput = document.getElementById("admin-theme");
    if (themeInput) themeInput.value = personalTheme || "default";
    const globalThemeInput = document.getElementById("admin-global-theme");
    if (globalThemeInput && document.activeElement !== globalThemeInput)
      globalThemeInput.value = branding.defaultTheme;
    const brandInput = document.getElementById("admin-brand-text");
    if (brandInput && document.activeElement !== brandInput) brandInput.value = brand;
    const preview = document.getElementById("admin-brand-preview");
    if (preview) preview.src = icon;
    const previewText = document.getElementById("admin-brand-preview-text");
    if (previewText) previewText.textContent = brand;
  }

  async function loadBranding() {
    try {
      const response = await fetch(base + "/branding.json", { cache: "no-store" });
      if (!response.ok) return;
      const next = await response.json();
      if (typeof next.name !== "string" || typeof next.icon !== "string" ||
          !next.icon.startsWith(base + "/")) return;
      branding = { name: next.name, icon: next.icon,
        defaultTheme: themes.includes(next.defaultTheme) ? next.defaultTheme : defaults.theme };
      applyPreferences();
    } catch { return; }
  }

  function pageName(url) {
    if (url.origin !== location.origin || !url.pathname.startsWith(base + "/")) return null;
    const name = url.pathname.slice(base.length + 1);
    return pages.has(name) ? name : null;
  }

  function updateNavigation() {
    const current = location.pathname.split("/").pop();
    const section = ["bucket", "object", "versions"].includes(current) ? "buckets" : current;
    for (const link of document.querySelectorAll(".sidebar-link, .mobile-nav-link")) {
      if (link.pathname.endsWith("/" + section)) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    }
  }

  function loading(active, label = "Loading page…") {
    clearTimeout(loadingTimer);
    const overlay = document.getElementById("page-loading");
    const content = document.querySelector(".workspace-content");
    if (!overlay || !content) return;
    if (active) {
      content.setAttribute("aria-busy", "true");
      overlay.setAttribute("message", label);
      loadingTimer = setTimeout(() => { overlay.show?.(); }, 140);
    } else {
      overlay.hide?.();
      content.removeAttribute("aria-busy");
    }
  }

  function navigationError(message) {
    const content = document.querySelector(".workspace-content");
    let notice = content.querySelector(".navigation-error");
    if (!notice) {
      notice = document.createElement("p");
      notice.className = "navigation-error";
      notice.setAttribute("role", "alert");
      content.prepend(notice);
    }
    notice.textContent = message;
    const toast = document.getElementById("admin-toast");
    if (toast?.matches(":defined")) toast.show(message, { variant: "danger", duration: 6000 });
  }

  async function mountPerformance() {
    if (location.pathname !== base + "/performance") return;
    if (window.ObjectStorePerformance) {
      window.ObjectStorePerformance.mount();
      return;
    }
    if (!performanceScript) {
      performanceScript = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = base + "/performance.js";
        script.onload = resolve;
        script.onerror = reject;
        document.head.append(script);
      });
    }
    try { await performanceScript; }
    catch {
      performanceScript = null;
      navigationError("Live charts could not load. Reload this page to try again.");
    }
  }

  async function navigate(input, mode = "push", options = {}) {
    const url = new URL(input, location.href);
    if (!pageName(url)) { location.assign(url.href); return; }
    if (mode === "push") history.replaceState({ scrollY: window.scrollY }, "", location.href);
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    loading(true, options.label);
    try {
      const response = await fetch(url, {
        credentials: "same-origin", cache: "no-store", signal: controller.signal
      });
      const destination = new URL(response.url);
      if (!pageName(destination)) { location.assign(destination.href); return; }
      if (!response.headers.get("Content-Type")?.startsWith("text/html"))
        throw new Error("The server returned an unexpected response.");
      const parsed = new DOMParser().parseFromString(await response.text(), "text/html");
      if (controller.signal.aborted) return;
      const next = parsed.querySelector(".workspace-content");
      if (!next) { location.assign(destination.href); return; }
      window.ObjectStorePerformance?.unmount();
      document.querySelector(".workspace-content").replaceWith(document.importNode(next, true));
      if (mode === "push") history.pushState({ scrollY: 0 }, "", destination.href);
      document.title = parsed.title || "ObjectStore admin";
      updateNavigation();
      mountContent(document.querySelector(".workspace-content"));
      window.scrollTo(0, mode === "pop" ? options.scrollY || 0 : 0);
      void mountPerformance();
    } catch (error) {
      if (error.name !== "AbortError")
        navigationError(error.message || "The page could not be loaded. Try again.");
    } finally {
      if (pending === controller) { pending = null; loading(false); }
    }
  }

  if (globalThis.Biscuit?.registerCodeLanguage) {
    try {
      Biscuit.registerCodeLanguage("json", { rules: [
        { type: "property", pattern: /"(?:\\.|[^"\\])*"(?=\s*:)/ },
        { type: "string", pattern: /"(?:\\.|[^"\\])*"/ },
        { type: "number", pattern: /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/ },
        { type: "keyword", pattern: /\b(?:true|false|null)\b/ }
      ] });
    } catch (error) {
      console.error("JSON highlighting unavailable", error);
    }
  }

  function mountContent(root) {
    applyPreferences();
    const bulkForm = root.querySelector("form[action$='/bulk-start']");
    if (bulkForm) {
      const operation = bulkForm.elements.operation;
      const update = () => {
        for (const field of bulkForm.querySelectorAll("[data-bulk-only]")) {
          const visible = field.dataset.bulkOnly === operation.value;
          field.hidden = !visible;
          field.querySelector("input").required = visible &&
            ["targetBucket", "tag", "confirm"].includes(field.querySelector("input").name);
        }
      };
      operation.addEventListener("change", update);
      update();
    }
    const liveJobs = [...root.querySelectorAll("[data-job-id]")]
      .filter(row => row.querySelector("form[action$='/bulk-cancel']"));
    if (liveJobs.length) {
      const poll = async () => {
        if (!root.isConnected) return;
        for (const row of liveJobs) {
          if (!row.querySelector("form[action$='/bulk-cancel']")) continue;
          try {
            const response = await fetch(base + "/jobs.json?id=" + row.dataset.jobId,
              { cache: "no-store", signal: AbortSignal.timeout(10000) });
            if (!response.ok) continue;
            const job = await response.json();
            if (!root.isConnected) return;
            row.querySelector("[data-job-state]").textContent = job.state;
            row.querySelector("[data-job-counts]").textContent =
              `${job.processed} processed · ${job.succeeded} succeeded · ${job.failed} failed`;
            if (job.finished) {
              row.querySelector("form[action$='/bulk-cancel']")?.remove();
            }
          } catch { /* The next poll may succeed. */ }
        }
        if (root.isConnected && liveJobs.some(row => row.querySelector("form[action$='/bulk-cancel']")))
          setTimeout(poll, 1500);
      };
      setTimeout(poll, 1500);
    }
    root.querySelector("#diagnostic-probe")?.addEventListener("click", async event => {
      const button = event.currentTarget;
      const result = root.querySelector("#diagnostic-probe-result");
      button.disabled = true;
      result.textContent = "Measuring…";
      const started = performance.now();
      try {
        const response = await fetch(base + "/diagnostics/probe.bin", { cache: "no-store" });
        if (!response.ok || !response.body) throw new Error("Probe unavailable");
        const firstByte = performance.now();
        const reader = response.body.getReader();
        let bytes = 0;
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          bytes += value.byteLength;
        }
        const elapsed = performance.now() - started;
        result.textContent = `${Math.round(firstByte - started)} ms to headers · ` +
          `${Math.round(elapsed)} ms total · ${(bytes / 1048576 / (elapsed / 1000)).toFixed(1)} MiB/s`;
      } catch {
        result.textContent = "Probe failed. Check the connection and try again.";
      } finally {
        button.disabled = false;
      }
    });
    const bucketTabs = root.querySelector("biscuit-tablayout[aria-label='Bucket panels']");
    if (bucketTabs) {
      const selected = location.hash.slice(1);
      if (["objects", "activity", "settings"].includes(selected)) bucketTabs.value = selected;
      bucketTabs.addEventListener("change", () => {
        history.replaceState(history.state, "", location.pathname + location.search + "#" + bucketTabs.value);
      });
    }
    root.querySelector(".bucket-pagination")?.addEventListener("change", event => {
      const params = new URLSearchParams();
      const filter = event.currentTarget.dataset.filter;
      if (filter) params.set("filter", filter);
      params.set("page", String(event.detail.page));
      void navigate(base + "/buckets?" + params, "push", { label: "Loading buckets…" });
    });
    const wizard = root.querySelector("#create-bucket-wizard");
    if (wizard) {
      const form = wizard.closest("form");
      const accessChecks = [...form.querySelectorAll("[name=blockPublicAcls], [name=ignorePublicAcls], " +
        "[name=blockPublicPolicy], [name=restrictPublicBuckets]")];
      const ackRow = form.querySelector("#public-ack-row");
      const acknowledge = ackRow.querySelector("input");
      const review = form.querySelector("#bucket-wizard-review");
      const updateReview = () => {
        const name = form.elements.bucket.value || "this bucket";
        const versioning = form.elements.versioning.value === "enabled" ? "on" : "off";
        const ownership = form.elements.ownership.selectedOptions[0]?.textContent.split(" · ")[0];
        review.textContent = `Create ${name} with versioning ${versioning}, ${ownership}, and ` +
          (accessChecks.every(input => input.checked) ? "all public access controls on." :
            "some public access controls off.");
      };
      const updateAccess = () => {
        const relaxed = accessChecks.some(input => !input.checked);
        ackRow.hidden = !relaxed;
        acknowledge.required = relaxed;
        if (!relaxed) acknowledge.checked = false;
        updateReview();
      };
      accessChecks.forEach(input => input.addEventListener("change", updateAccess));
      wizard.addEventListener("stepchange", updateReview);
      form.addEventListener("submit", event => {
        if (wizard.currentStep < wizard.steps.length - 1) {
          event.preventDefault();
          wizard.next();
        }
      });
      wizard.closest("biscuit-dialogview")?.addEventListener("close", () => {
        form.reset();
        wizard.currentStep = 0;
        updateAccess();
      });
      updateAccess();
    }
    const themeInput = root.querySelector("#admin-theme");
    themeInput?.addEventListener("change", () => {
      if (themeInput.value === "default") clear("theme");
      else if (themes.includes(themeInput.value)) save("theme", themeInput.value);
      applyPreferences();
    });
    const iconInput = root.querySelector("#admin-brand-icon");
    iconInput?.addEventListener("change", async () => {
      const file = iconInput.files?.[0];
      if (!file) return;
      const status = root.querySelector("#admin-settings-status");
      if (!["image/png", "image/jpeg", "image/webp"].includes(file.type) || file.size > 131072) {
        status.textContent = "Choose a PNG, JPEG, or WebP image under 128 KiB.";
        return;
      }
      iconInput.disabled = true;
      status.textContent = "Uploading icon…";
      try {
        const response = await fetch(base + "/branding-icon/upload", {
          method: "POST", body: file, credentials: "same-origin",
          headers: { "Content-Type": file.type, "X-CSRF-Token": iconInput.dataset.csrf }
        });
        if (!response.ok) throw new Error(await response.text());
        await loadBranding();
        status.textContent = "Icon saved on this server.";
      } catch (error) {
        status.textContent = error.message || "Could not save the icon.";
      } finally {
        iconInput.disabled = false;
      }
    });
    for (const button of root.querySelectorAll("[data-open-dialog]")) {
      button.addEventListener("click", () => {
        const dialog = root.querySelector("#" + button.dataset.openDialog);
        dialog?.show?.();
        dialog?.querySelector("input:not([type=hidden])")?.focus();
      });
    }
    for (const button of root.querySelectorAll("[data-close-dialog]")) {
      button.addEventListener("click", () => button.closest("biscuit-dialogview")?.hide?.());
    }
    for (const form of root.querySelectorAll(".code-form")) {
      const editor = form.querySelector("biscuit-codeview");
      const fallback = form.querySelector("textarea.code-fallback");
      const diagnostics = form.querySelector(".editor-diagnostics");
      const field = fallback?.getAttribute("name");
      if (!editor || !fallback || !diagnostics || !field) continue;

      const validate = () => {
        const value = editor.matches(":defined") ? editor.value : fallback.value;
        if (editor.getAttribute("language") === "json" && value.trim()) {
          try {
            JSON.parse(value);
          } catch (error) {
            diagnostics.dataset.state = "error";
            diagnostics.textContent = "JSON syntax: " + error.message;
            return false;
          }
        }
        if (field === "content" && new TextEncoder().encode(value).length > 32768) {
          diagnostics.dataset.state = "error";
          diagnostics.textContent = "Text files are limited to 32 KiB in the admin editor.";
          return false;
        }
        diagnostics.dataset.state = "ok";
        diagnostics.textContent = field === "policy" ?
          "JSON syntax looks valid. Policy permissions are checked when saved." :
          "Ready to save as a new object version.";
        return true;
      };

      customElements.whenDefined("biscuit-codeview").then(() => {
        if (!form.isConnected) return;
        editor.name = field;
        fallback.removeAttribute("name");
        fallback.hidden = true;
        form.dataset.enhanced = "";
        editor.addEventListener("input", validate);
      });
      fallback.addEventListener("input", validate);
      form.addEventListener("submit", (event) => {
        if (validate()) return;
        event.preventDefault();
        editor.shadowRoot?.querySelector("textarea")?.focus();
      });
    }

    for (const form of root.querySelectorAll(".upload-form")) {
      const fileInput = form.elements.namedItem("file");
      const keyInput = form.elements.namedItem("key");
      const overwriteInput = form.elements.namedItem("overwrite");
      const button = form.querySelector("button[type=submit]");
      const status = form.querySelector(".upload-status");
      const prefix = form.dataset.prefix || "";
      const progress = document.createElement("progress");
      progress.className = "upload-progress";
      progress.max = 100;
      progress.hidden = true;
      progress.setAttribute("aria-label", "Upload progress");
      status.after(progress);
      fileInput.addEventListener("change", () => {
        if (fileInput.files[0] && (!keyInput.value || keyInput.value === prefix))
          keyInput.value = prefix + fileInput.files[0].name;
      });
      status.textContent = "Choose a file, then upload it to this bucket.";
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        const file = fileInput.files[0];
        if (!file) return;
        const key = keyInput.value.trim();
        if (!key) {
          status.textContent = "Enter an object key.";
          return;
        }
        if (file.size > Number(form.dataset.maxBytes)) {
          status.textContent = "This file exceeds the configured object limit.";
          return;
        }
        const query = new URLSearchParams({ bucket: form.dataset.bucket, key });
        if (overwriteInput.checked) query.set("overwrite", "1");
        button.disabled = true;
        form.dataset.busy = "";
        progress.hidden = false;
        progress.removeAttribute("value");
        status.textContent = "Uploading…";
        const request = new XMLHttpRequest();
        request.open("POST", base + "/upload?" + query);
        request.withCredentials = true;
        request.setRequestHeader("Content-Type", file.type || "application/octet-stream");
        request.setRequestHeader("X-ObjectStore-CSRF", form.elements.namedItem("csrf").value);
        request.upload.onprogress = (event) => {
          if (!event.lengthComputable) return;
          const percent = Math.round(event.loaded / event.total * 100);
          progress.value = percent;
          status.textContent = "Uploading… " + percent + "%";
        };
        request.onload = () => {
          if (request.status === 201 && request.getResponseHeader("Location")) {
            status.textContent = "Upload complete. Opening object…";
            void navigate(request.getResponseHeader("Location"), "push").finally(() => {
              if (!form.isConnected) return;
              button.disabled = false;
              delete form.dataset.busy;
              progress.hidden = true;
            });
            return;
          }
          status.textContent = request.responseText || "Upload failed.";
          button.disabled = false;
          delete form.dataset.busy;
          progress.hidden = true;
        };
        request.onerror = () => {
          status.textContent = "Connection lost during upload. Check the object list before retrying.";
          button.disabled = false;
          delete form.dataset.busy;
          progress.hidden = true;
        };
        request.send(file);
      });
    }
  }

  document.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey ||
        event.shiftKey || event.altKey) return;
    const link = event.composedPath().find(node => node instanceof HTMLAnchorElement && node.href);
    if (!link || link.hasAttribute("download") || link.target && link.target !== "_self") return;
    const url = new URL(link.href);
    if (!pageName(url) || url.hash && url.pathname === location.pathname &&
        url.search === location.search) return;
    event.preventDefault();
    void navigate(url);
  });

  document.addEventListener("submit", (event) => {
    if (event.defaultPrevented) return;
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.closest(".workspace-content")) return;
    if (form.action.endsWith("/action/access-check")) {
      event.preventDefault();
      pending?.abort();
      const controller = new AbortController();
      pending = controller;
      loading(true, "Checking access…");
      void (async () => {
        try {
          const response = await fetch(form.action, {
            method: "POST", body: new URLSearchParams(new FormData(form)),
            credentials: "same-origin", cache: "no-store", signal: controller.signal
          });
          const destination = new URL(response.url);
          if (!pageName(destination)) { location.assign(destination.href); return; }
          if (!response.headers.get("Content-Type")?.startsWith("text/html"))
            throw new Error("The access check did not return a page.");
          const parsed = new DOMParser().parseFromString(await response.text(), "text/html");
          if (controller.signal.aborted || !form.isConnected) return;
          const next = parsed.querySelector(".workspace-content");
          if (!next) throw new Error("The access check could not be displayed.");
          window.ObjectStorePerformance?.unmount();
          document.querySelector(".workspace-content").replaceWith(document.importNode(next, true));
          history.replaceState(history.state, "", base + "/audit");
          mountContent(document.querySelector(".workspace-content"));
          if (!response.ok) navigationError("Access could not be checked. Review the details below.");
        } catch (error) {
          if (error.name !== "AbortError") navigationError(error.message || "Access check failed.");
        } finally {
          if (pending === controller) { pending = null; loading(false); }
        }
      })();
      return;
    }
    if (form.method.toUpperCase() === "GET" && pageName(new URL(form.action))) {
      event.preventDefault();
      const url = new URL(form.action);
      for (const [key, value] of new FormData(form)) url.searchParams.set(key, value);
      void navigate(url, "push", { label: "Opening results…" });
    } else if (form.method.toUpperCase() === "POST" && !form.classList.contains("upload-form")) {
      form.dataset.busy = "";
      loading(true, "Saving changes…");
    }
  });

  applyPreferences();
  void loadBranding();
  matchMedia("(prefers-color-scheme: light)").addEventListener("change", applyPreferences);
  if (document.querySelector(".workspace-content")) {
    history.replaceState({ scrollY: window.scrollY }, "", location.href);
    window.addEventListener("popstate", (event) => {
      void navigate(location.href, "pop", { scrollY: event.state?.scrollY || 0 });
    });
    updateNavigation();
    mountContent(document.querySelector(".workspace-content"));
    loading(false);
  }
})();
