"use strict";

const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const repo = path.resolve(__dirname, "../../..");
const moduleDirectory = path.join(repo, "app/app/src/main/assets/modules/presentation");
const fixtureDirectory = path.join(__dirname, "fixtures");
const captureDirectory = path.join(__dirname, "captures");
const resultsFile = path.join(__dirname, "composition-viewer-results.json");
const fixtures = [
  "free-cover",
  "free-columns",
  "free-chart-insights",
  "free-table-long-title",
];
const contentTypes = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};
const csp = "default-src 'self' data: blob:; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
  "script-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; frame-src 'none'; " +
  "base-uri 'none'; form-action 'none'; worker-src 'none'; child-src 'none'";
const artifactReads = new Map();

function response(response, file, body) {
  response.writeHead(200, {
    "Content-Type": contentTypes[path.extname(file)] || "application/octet-stream",
    "Content-Security-Policy": csp,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(body === undefined ? fs.readFileSync(file) : body);
}

const server = http.createServer((request, reply) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname === "/viewer/presentation.html") {
    const fixture = url.searchParams.get("fixture");
    if (!fixtures.includes(fixture)) {
      reply.writeHead(404).end("Unknown fixture");
      return;
    }
    const source = fs.readFileSync(path.join(moduleDirectory, "viewer/presentation.html"), "utf8");
    const artifactUrl = "/module-artifacts/" + encodeURIComponent(fixture);
    const bootstrap = "<script>window.JarvysArtifact={url:" + JSON.stringify(artifactUrl) + "};</script>";
    response(reply, "presentation.html", source.replace(/<head(?:\s[^>]*)?>/i, (head) => head + bootstrap));
    return;
  }
  const assetMatch = url.pathname.match(/^\/(viewer|vendor)\/([a-zA-Z0-9._-]+)$/);
  if (assetMatch) {
    const file = path.resolve(moduleDirectory, assetMatch[1], assetMatch[2]);
    const root = path.resolve(moduleDirectory, assetMatch[1]) + path.sep;
    if (!file.startsWith(root) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      reply.writeHead(404).end("Asset not found");
      return;
    }
    response(reply, file);
    return;
  }
  const artifactMatch = url.pathname.match(/^\/module-artifacts\/([a-zA-Z0-9-]+)$/);
  if (artifactMatch && fixtures.includes(artifactMatch[1])) {
    const fixture = artifactMatch[1];
    const file = path.join(fixtureDirectory, fixture + ".pptx");
    if (!fs.existsSync(file)) {
      reply.writeHead(404).end("Generate fixtures first");
      return;
    }
    artifactReads.set(fixture, (artifactReads.get(fixture) || 0) + 1);
    response(reply, file);
    return;
  }
  reply.writeHead(404).end("Not found");
});

async function main() {
  const puppeteerPath = process.env.PUPPETEER_CORE_PATH || path.join(
    process.env.LOCALAPPDATA || process.env.TEMP || process.cwd(),
    "Temp", "jarvys-pptx-eval-runner", "node_modules", "puppeteer-core",
  );
  const puppeteer = require(puppeteerPath);
  const chrome = process.env.PPTX_EVAL_CHROME || path.join(
    process.env.LOCALAPPDATA,
    "Google", "Chrome", "Application", "chrome.exe",
  );
  for (const fixture of fixtures) {
    if (!fs.existsSync(path.join(fixtureDirectory, fixture + ".pptx"))) {
      throw new Error("Missing fixture " + fixture + "; run generate-fixtures.cjs first.");
    }
  }
  fs.mkdirSync(captureDirectory, { recursive: true });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = "http://127.0.0.1:" + server.address().port;
  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: ["--disable-gpu", "--hide-scrollbars"],
  });
  const results = [];

  try {
    process.stdout.write("Final production viewer: " + await browser.version() + "; viewport 360x800 CSS px; deviceScaleFactor 3\n");
    for (const fixture of fixtures) {
      const page = await browser.newPage();
      const externalRequests = [];
      const pageErrors = [];
      page.on("request", (request) => {
        if (/^https?:/.test(request.url()) && new URL(request.url()).origin !== baseUrl) externalRequests.push(request.url());
      });
      page.on("pageerror", (error) => pageErrors.push(error.message));
      await page.setViewport({ width: 360, height: 800, deviceScaleFactor: 3 });
      await page.emulateMediaFeatures([{ name: "prefers-color-scheme", value: "dark" }]);
      try {
        await page.goto(baseUrl + "/viewer/presentation.html?fixture=" + encodeURIComponent(fixture), {
          waitUntil: "networkidle0", timeout: 30000,
        });
        await page.waitForFunction(() => window.PptxPresentationViewerReady !== undefined, { timeout: 30000 });
        await page.evaluate(() => window.PptxPresentationViewerReady);
        const slideCount = await page.evaluate(() => {
          const counter = document.getElementById("counter").textContent;
          return Number((counter.match(/\d+/g) || []).slice(-1)[0]);
        });
        if (!slideCount) throw new Error("Viewer did not report a slide count for " + fixture);
        for (let slideIndex = 0; slideIndex < slideCount; slideIndex += 1) {
          if (slideIndex > 0) {
            const before = await page.$eval("#stage img", (image) => image.getAttribute("src"));
            await page.click("#next");
            await page.waitForFunction((previousSource, expected) => {
              const counter = document.getElementById("counter").textContent;
              const values = counter.match(/\d+/g) || [];
              const image = document.querySelector("#stage img");
              return Number(values[0]) === expected && image && image.getAttribute("src") !== previousSource;
            }, { timeout: 30000 }, before, slideIndex + 1);
          }
          const state = await page.evaluate(() => {
            const image = document.querySelector("#stage img");
            const rectangle = image && image.getBoundingClientRect();
            const stage = document.getElementById("stage").getBoundingClientRect();
            return {
              counter: document.getElementById("counter").textContent,
              imageVisible: Boolean(image && rectangle.width > 0 && rectangle.height > 0),
              bounds: rectangle && { left: rectangle.left, top: rectangle.top, width: rectangle.width, height: rectangle.height },
              stage: { left: stage.left, top: stage.top, width: stage.width, height: stage.height },
              failure: document.getElementById("status").hidden ? "" : document.getElementById("status").textContent,
              title: document.title,
            };
          });
          if (!state.imageVisible) throw new Error("Slide image is blank: " + fixture + " slide " + (slideIndex + 1));
          if (state.failure) throw new Error("Viewer failure: " + state.failure);
          const captureName = "final-viewer-" + fixture + "-slide" + (slideIndex + 1) + ".png";
          await page.screenshot({ path: path.join(captureDirectory, captureName), fullPage: false });
          results.push({ fixture, slideIndex: slideIndex + 1, slideCount, capture: captureName, ...state });
          process.stdout.write(fixture + " / slide " + (slideIndex + 1) + ": " + state.counter + " / " + state.bounds.width.toFixed(1) + "x" + state.bounds.height.toFixed(1) + " px\n");
        }
        if (externalRequests.length) throw new Error("Viewer requested external resources: " + externalRequests.join(", "));
        if (pageErrors.length) throw new Error("Browser error in viewer: " + pageErrors.join("; "));
        if (artifactReads.get(fixture) !== 1) throw new Error("Expected one allowlisted PPTX byte read for " + fixture);
      } finally {
        await page.close();
      }
    }
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }

  fs.writeFileSync(resultsFile, JSON.stringify({ csp, artifactReads: Object.fromEntries(artifactReads), results }, null, 2) + "\n");
  process.stdout.write("Wrote " + results.length + " final viewer captures and " + path.basename(resultsFile) + "\n");
}

main().catch((error) => {
  process.stderr.write((error && error.stack) || String(error));
  process.exitCode = 1;
  server.close();
});
