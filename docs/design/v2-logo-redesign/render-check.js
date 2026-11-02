(async () => {
  const expected = window.RENDER_EXPECT || { width: 1600, height: 0 };
  try {
    await document.fonts.ready;
    await Promise.all(
      Array.from(document.images).map((image) =>
        image.complete
          ? image.decode().catch(() => undefined)
          : new Promise((resolve) => {
              image.addEventListener("load", resolve, { once: true });
              image.addEventListener("error", resolve, { once: true });
            }),
      ),
    );
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

    const failedImages = Array.from(document.images)
      .filter((image) => !image.complete || image.naturalWidth === 0)
      .map((image) => image.getAttribute("src"));
    const isActual24 = (image) => {
      const rect = image.getBoundingClientRect();
      return Math.abs(rect.width - 24) <= 0.25 && Math.abs(rect.height - 24) <= 0.25;
    };
    const insideViewport = (element) => {
      const rect = element.getBoundingClientRect();
      return (
        rect.top >= 0 &&
        rect.left >= 0 &&
        rect.right <= document.documentElement.clientWidth &&
        rect.bottom <= document.documentElement.clientHeight
      );
    };

    let proofEvidenceOk = true;
    let proofSizes = null;
    if (document.body.classList.contains("proof")) {
      const activePanel = document.querySelector(".proof-panel.active");
      const light = activePanel?.querySelectorAll('[data-evidence="mono-24-light"] img') || [];
      const dark = activePanel?.querySelectorAll('[data-evidence="mono-24-dark"] img') || [];
      const full = activePanel?.querySelectorAll(".master-root, .size-strip .size-item img") || [];
      const dimensions = Array.from(full).map((image) => {
        const rect = image.getBoundingClientRect();
        return { width: Math.round(rect.width), height: Math.round(rect.height) };
      });
      proofSizes = dimensions.map(({ width }) => width).sort((a, b) => a - b);
      proofEvidenceOk =
        light.length === 1 &&
        dark.length === 1 &&
        Array.from(light).every(isActual24) &&
        Array.from(dark).every(isActual24) &&
        JSON.stringify(proofSizes) === JSON.stringify([24, 48, 96, 512]) &&
        dimensions.every(({ width, height }) => width === height);
    }

    let matrixEvidenceOk = true;
    let matrix = null;
    if (document.body.classList.contains("qa-sheet")) {
      const light = document.querySelectorAll('[data-evidence="mono-24-light"] img');
      const dark = document.querySelectorAll('[data-evidence="mono-24-dark"] img');
      const matrixRows = document.querySelectorAll(".theme-matrix-row-label");
      const matrixCells = document.querySelectorAll(".theme-cell");
      const matrixImages = document.querySelectorAll(".theme-cell img");
      const matrixElement = document.querySelector(".theme-matrix");
      matrix = {
        light: light.length,
        dark: dark.length,
        rows: matrixRows.length,
        cells: matrixCells.length,
        images: matrixImages.length,
        visible: matrixElement ? insideViewport(matrixElement) : false,
      };
      matrixEvidenceOk =
        light.length === 3 &&
        dark.length === 3 &&
        Array.from(light).every(isActual24) &&
        Array.from(dark).every(isActual24) &&
        matrixRows.length === 3 &&
        matrixCells.length === 15 &&
        matrixImages.length === 15 &&
        Array.from(matrixImages).every(isActual24) &&
        matrix.visible;
    }

    const status = {
      width: document.documentElement.clientWidth,
      height: document.documentElement.clientHeight,
      scrollWidth: document.documentElement.scrollWidth,
      scrollHeight: document.documentElement.scrollHeight,
      expectedWidth: expected.width,
      expectedHeight: expected.height,
      failedImages,
      proofEvidenceOk,
      proofSizes,
      matrixEvidenceOk,
      matrix,
    };
    status.ok =
      status.width === expected.width &&
      status.height === expected.height &&
      status.scrollWidth <= expected.width &&
      status.scrollHeight <= expected.height &&
      failedImages.length === 0 &&
      proofEvidenceOk &&
      matrixEvidenceOk;

    document.body.dataset.renderReady = status.ok ? "true" : "false";
    document.body.dataset.renderStatus = JSON.stringify(status);
  } catch (error) {
    document.body.dataset.renderReady = "false";
    document.body.dataset.renderStatus = JSON.stringify({
      ok: false,
      error: String(error),
    });
  }
})();
