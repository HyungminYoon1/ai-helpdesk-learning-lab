import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath, URL } from "node:url";
import { createToolDispatcher, runFakeToolSpike } from "../../../scripts/week7-tool-calling-spike.mjs";

function fixture() {
    const calls = [];
    const dispatcher = createToolDispatcher({ currentTicketId: 7, findCurrentTicket: id => {
        calls.push(id);
        return { id, status: "OPEN" };
    } });
    return { calls, ...dispatcher };
}

function rawCall(args = {}) {
    return JSON.stringify({ name: "find_current_ticket", arguments: args });
}

test("allowed name and empty arguments execute once with the server-bound ticket ID", () => {
    const dispatcher = fixture();
    assert.deepEqual(dispatcher.dispatch(rawCall()), {
        code: "TOOL_SUCCEEDED", toolExecuted: true, value: { id: 7, status: "OPEN" }
    });
    assert.deepEqual(dispatcher.calls, [7]);
    assert.equal(dispatcher.getExecutionCount(), 1);
});

test("an unlisted state-changing tool is rejected before any function executes", () => {
    const dispatcher = fixture();
    assert.deepEqual(dispatcher.dispatch(JSON.stringify({ name: "resolve_ticket", arguments: {} })), {
        code: "TOOL_NOT_ALLOWED", toolExecuted: false
    });
    assert.deepEqual(dispatcher.calls, []);
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("prototype property names cannot select a function", () => {
    const dispatcher = fixture();
    for (const name of ["__proto__", "constructor", "toString"]) {
        assert.equal(dispatcher.dispatch(JSON.stringify({ name, arguments: {} })).code, "TOOL_NOT_ALLOWED");
    }
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("a model-supplied ticket ID is rejected rather than ignored, executed, or retried", () => {
    const dispatcher = fixture();
    assert.deepEqual(dispatcher.dispatch(rawCall({ ticketId: 9999 })), {
        code: "INVALID_TOOL_ARGUMENTS", toolExecuted: false
    });
    assert.deepEqual(dispatcher.calls, []);
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("only an empty argument object is accepted without type coercion", () => {
    const dispatcher = fixture();
    for (const args of [null, [], "{}", 7, true, { role: "AGENT" }, { unexpected: null }]) {
        assert.equal(dispatcher.dispatch(rawCall(args)).code, "INVALID_TOOL_ARGUMENTS");
    }
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("malformed JSON and non-object roots are rejected without executing", () => {
    const dispatcher = fixture();
    for (const raw of [undefined, {}, "", "{", "null", "[]", "7", '"text"', "true"]) {
        assert.equal(dispatcher.dispatch(raw).code, "INVALID_TOOL_REQUEST");
    }
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("missing or extra root fields cannot override the trusted server context", () => {
    const dispatcher = fixture();
    for (const value of [{}, { name: "find_current_ticket" }, { arguments: {} },
        { name: 7, arguments: {} }, { name: "find_current_ticket", arguments: {}, ticketId: 9999 },
        { name: "find_current_ticket", arguments: {}, role: "AGENT" }]) {
        assert.equal(dispatcher.dispatch(JSON.stringify(value)).code, "INVALID_TOOL_REQUEST");
    }
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("oversized UTF-8 requests are rejected before JSON processing or execution", () => {
    const dispatcher = fixture();
    assert.equal(dispatcher.dispatch("가".repeat(1500)).code, "INVALID_TOOL_REQUEST");
    assert.equal(dispatcher.getExecutionCount(), 0);
});

test("the trusted ticket binding requires a positive safe integer and a fake function", () => {
    for (const currentTicketId of [0, -1, 1.5, "7", NaN, Number.MAX_SAFE_INTEGER + 1]) {
        assert.throws(() => createToolDispatcher({ currentTicketId, findCurrentTicket: () => null }),
            /INVALID_SERVER_TICKET_ID/);
    }
    assert.throws(() => createToolDispatcher({ currentTicketId: 7, findCurrentTicket: null }),
        /FAKE_TOOL_FUNCTION_REQUIRED/);
});

test("an execution failure counts once and returns no raw exception message or stack", () => {
    let attempts = 0;
    const dispatcher = createToolDispatcher({ currentTicketId: 7, findCurrentTicket: () => {
        attempts += 1;
        throw new Error("PRIVATE_EXCEPTION_MARKER");
    } });
    const result = dispatcher.dispatch(rawCall());
    assert.deepEqual(result, { code: "TOOL_FAILED", toolExecuted: true });
    assert.equal(attempts, 1);
    assert.equal(dispatcher.getExecutionCount(), 1);
    assert.equal(JSON.stringify(result).includes("PRIVATE_EXCEPTION_MARKER"), false);
});

test("only explicit accepted dispatches increase the counter and rejections never retry", () => {
    const dispatcher = fixture();
    dispatcher.dispatch(rawCall({ ticketId: 9999 }));
    assert.equal(dispatcher.getExecutionCount(), 0);
    dispatcher.dispatch(rawCall());
    dispatcher.dispatch(rawCall({ ticketId: 9999 }));
    assert.equal(dispatcher.getExecutionCount(), 1);
    dispatcher.dispatch(rawCall());
    assert.equal(dispatcher.getExecutionCount(), 2);
});

test("the four independent fake cases produce execution counts 1, 0, 0, 1", () => {
    const report = runFakeToolSpike();
    assert.equal(report.evidence, "OFFLINE_FAKE_TOOL_EXECUTION");
    assert.equal(report.providerCalls, 0);
    assert.equal(report.databaseOperations, 0);
    assert.equal(report.ticketMutations, 0);
    assert.deepEqual(report.results.map(item => item.toolExecutions), [1, 0, 0, 1]);
    assert.deepEqual(report.results.map(item => item.code), ["TOOL_SUCCEEDED", "TOOL_NOT_ALLOWED",
        "INVALID_TOOL_ARGUMENTS", "TOOL_FAILED"]);
});

test("CLI executes only fixed fake cases and refuses live or arbitrary arguments", () => {
    const script = fileURLToPath(new URL("../../../scripts/week7-tool-calling-spike.mjs", import.meta.url));
    const run = spawnSync(process.execPath, [script, "--run-fake-tools"], { encoding: "utf8" });
    assert.equal(run.status, 0);
    assert.deepEqual(JSON.parse(run.stdout).results.map(item => item.toolExecutions), [1, 0, 0, 1]);
    for (const args of [[], ["--live"], ["--run-fake-tools", "unexpected"]]) {
        const denied = spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });
        assert.equal(denied.status, 1);
        assert.ok(denied.stderr.includes("FAKE_TOOLS_ONLY"));
        assert.equal(denied.stdout, "");
    }
});
