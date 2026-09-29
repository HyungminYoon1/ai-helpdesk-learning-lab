import http from "node:http";

const port = Number(process.env.HELPDESK_CROSS_ORIGIN_PORT);

if (!Number.isSafeInteger(port) || port <= 0) {
    throw new Error("HELPDESK_CROSS_ORIGIN_PORT is required");
}

http.createServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    response.end("<!doctype html><html lang=\"ko\"><title>Cross-Origin Probe</title></html>");
}).listen(port, "127.0.0.1");
