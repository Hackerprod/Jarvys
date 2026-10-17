"use strict";

const fs = require("node:fs");
const path = require("node:path");

const defaultPuppeteerPath = path.join(
  process.env.LOCALAPPDATA || process.env.TEMP || process.cwd(),
  "Temp",
  "jarvys-pptx-eval-runner",
  "node_modules",
  "puppeteer-core",
);
const puppeteer = require(process.env.PUPPETEER_CORE_PATH || defaultPuppeteerPath);
const chrome = process.env.PPTX_EVAL_CHROME || path.join(
  process.env.LOCALAPPDATA,
  "Google",
  "Chrome",
  "Application",
  "chrome.exe",
);
const evaluationDirectory = __dirname;
const captureDirectory = path.join(evaluationDirectory, "captures");
const resultFile = path.join(evaluationDirectory, "results.jsonl");
const baseUrl = process.env.PPTX_EVAL_URL || "http://127.0.0.1:41773";
const allRenderers = ["pptxviewjs", "pptx-preview", "pptx-glimpse", "silurus"];
const allFixtures = ["title-text", "bar-chart", "table", "image-shape", "theme-light-dark"];
const selectedRenderer = process.argv[2] || "";
const selectedFixture = process.argv[3] || "";
const renderers = selectedRenderer ? [selectedRenderer] : allRenderers;
const fixtures = selectedFixture ? [selectedFixture] : allFixtures;

fs.mkdirSync(captureDirectory, { recursive: true });
fs.writeFileSync(resultFile, "", "utf8");

function slideIndices(fixture) {
  return fixture === "theme-light-dark" ? [0, 1, 2] : [0];
}

async function main() {
  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: ["--disable-gpu", "--hide-scrollbars"],
  });

  try {
    process.stdout.write(`Browser ${await browser.version()}; viewport 360x800 CSS px; deviceScaleFactor 3\n`);
    for (const renderer of renderers) {
      for (const fixture of fixtures) {
        for (const slideIndex of slideIndices(fixture)) {
          const captureName = `${renderer}-${fixture}-slide${slideIndex + 1}.png`;
          const capturePath = path.join(captureDirectory, captureName);
          const url = new URL("/eval.html", baseUrl);
          url.searchParams.set("renderer", renderer);
          url.searchParams.set("fixture", fixture);
          url.searchParams.set("slide", String(slideIndex));
          url.searchParams.set("capture", captureName);
          const page = await browser.newPage();
          await page.setViewport({ width: 360, height: 800, deviceScaleFactor: 3 });

          try {
            await page.goto(url.href, { waitUntil: "networkidle0", timeout: 30000 });
            await page.waitForFunction(() => document.body.dataset.ready === "true", { timeout: 30000 });
            await page.evaluate(async () => {
              if (window.evalReportPromise) await window.evalReportPromise;
            });
            await page.screenshot({ path: capturePath, fullPage: false });
            const result = JSON.parse(await page.$eval("#result", (node) => node.textContent));
            process.stdout.write(`${renderer} / ${fixture} / slide ${slideIndex + 1}: ${result.status} - ${result.renderMs} ms - ${result.message}\n`);
          } finally {
            await page.close();
          }
        }
      }
    }
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  process.stderr.write((error && error.stack) || String(error));
  process.exitCode = 1;
});
