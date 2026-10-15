// Local contract host for the actual compiled Kotlin browser backend. Not shipped by Next.js.

import { readFile } from "node:fs/promises";
import { createServer } from "node:http";

const assets = new Map([
  [
    "/runtime.mjs",
    [
      new URL("../lib/kmpwright-demo/kmpwright-demo.mjs", import.meta.url),
      "text/javascript",
    ],
  ],
  [
    "/playground.html",
    [new URL("../public/demo/playground.html", import.meta.url), "text/html"],
  ],
]);
createServer(async (request, response) => {
  const asset = assets.get(request.url);
  if (asset) {
    try {
      response.writeHead(200, { "Content-Type": asset[1] });
      response.end(await readFile(asset[0]));
    } catch {
      response.end();
    }
    return;
  }
  response.writeHead(200, { "Content-Type": "text/html" });
  response.end(
    '<!doctype html><html lang="en"><title>Browser contracts</title><iframe title="Contract page" src="/playground.html"></iframe></html>',
  );
}).listen(3212, "127.0.0.1");
