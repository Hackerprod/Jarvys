"use strict";

const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const repo = path.resolve(process.argv[2]);
const packed = path.resolve(process.argv[3]);
const moduleDirectory = path.join(repo, "app/app/src/main/assets/modules/presentation");
const evaluationDirectory = path.join(repo, "app/docs/pptx-viewer-eval");
const packages = {
  pptxviewjs: path.join(packed, "pptxviewjs-1.1.9", "package"),
  glimpse: path.join(packed, "pptx-glimpse-5.3.1", "package"),
  preview: path.join(packed, "pptx-preview-1.0.7", "package"),
  silurus: path.join(packed, "silurus-ooxml-0.88.0", "package"),
};
const contentTypes = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".cjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".wasm": "application/wasm",
  ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};

function resolvePath(urlPath) {
  if (urlPath === "/eval.html") return path.join(evaluationDirectory, "eval.html");
  for (const [prefix, root] of [["/assets/", moduleDirectory], ["/fixtures/", path.join(evaluationDirectory, "fixtures")]]) {
    if (urlPath.startsWith(prefix)) return containedPath(root, urlPath.slice(prefix.length));
  }
  const chartVendor = path.join(repo, "app/app/src/main/assets/modules/chart/vendor");
  if (urlPath.startsWith("/candidate-assets/chartjs/")) {
    return containedPath(chartVendor, urlPath.slice("/candidate-assets/chartjs/".length));
  }
  for (const [name, root] of Object.entries(packages)) {
    const prefix = "/candidates/" + name + "/package/";
    if (urlPath.startsWith(prefix)) return containedPath(root, urlPath.slice(prefix.length));
  }
  return null;
}

function containedPath(root, relative) {
  const resolved = path.resolve(root, relative);
  const fromRoot = path.relative(root, resolved);
  return fromRoot && fromRoot !== ".." && !fromRoot.startsWith(".." + path.sep) && !path.isAbsolute(fromRoot)
    ? resolved
    : null;
}

const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, "http://127.0.0.1").pathname;
  if (pathname === "/report" && request.method === "POST") {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      fs.appendFileSync(path.join(evaluationDirectory, "results.jsonl"), body + "\n", "utf8");
      response.writeHead(204, { "Cache-Control": "no-store" });
      response.end();
    });
    return;
  }
  const file = resolvePath(pathname);
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Not found");
    return;
  }
  const headers = {
    "Content-Type": contentTypes[path.extname(file)] || "application/octet-stream",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'self' data: blob:; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'; child-src 'none'",
  };
  response.writeHead(200, headers);
  fs.createReadStream(file).pipe(response);
});

server.listen(41773, "127.0.0.1", () => process.stdout.write("PPTX evaluation server on 127.0.0.1:41773\n"));
