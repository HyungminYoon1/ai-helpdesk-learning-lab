import { pathToFileURL } from "node:url";

const ALLOWED_TOOL = "find_current_ticket";
const MAX_REQUEST_BYTES = 4096;

function isObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

function rejected(code) {
    return { code, toolExecuted: false };
}

// 독립 학습용 동기 Dispatcher다. Provider·Database·Spring에 연결하지 않는다.
export function createToolDispatcher({ currentTicketId, findCurrentTicket }) {
    if (!Number.isSafeInteger(currentTicketId) || currentTicketId <= 0) {
        throw new Error("INVALID_SERVER_TICKET_ID");
    }
    if (typeof findCurrentTicket !== "function") {
        throw new Error("FAKE_TOOL_FUNCTION_REQUIRED");
    }

    let executionCount = 0;

    function dispatch(rawCall) {
        if (typeof rawCall !== "string" || Buffer.byteLength(rawCall, "utf8") > MAX_REQUEST_BYTES) {
            return rejected("INVALID_TOOL_REQUEST");
        }

        let call;
        try {
            call = JSON.parse(rawCall);
        } catch {
            return rejected("INVALID_TOOL_REQUEST");
        }

        // 이 Spike의 단순화한 입력 형식이다. 실제 Provider 응답 형식과 구분한다.
        if (!isObject(call) || Object.keys(call).length !== 2
            || !Object.hasOwn(call, "name") || !Object.hasOwn(call, "arguments")
            || typeof call.name !== "string") {
            return rejected("INVALID_TOOL_REQUEST");
        }
        if (call.name !== ALLOWED_TOOL) {
            return rejected("TOOL_NOT_ALLOWED");
        }
        if (!isObject(call.arguments) || Object.keys(call.arguments).length !== 0) {
            return rejected("INVALID_TOOL_ARGUMENTS");
        }

        // 검증을 모두 통과한 뒤, Server가 정한 ID로만 실행한다. 자동 재시도는 없다.
        executionCount += 1;
        try {
            const value = findCurrentTicket(currentTicketId);
            return { code: "TOOL_SUCCEEDED", toolExecuted: true, value };
        } catch {
            // 원문 Exception Message·Stack·요청 값을 결과에 복사하지 않는다.
            return { code: "TOOL_FAILED", toolExecuted: true };
        }
    }

    return { dispatch, getExecutionCount: () => executionCount };
}

export function runFakeToolSpike() {
    const cases = [
        { caseId: "T01_VALID", name: ALLOWED_TOOL, arguments: {} },
        { caseId: "T02_FORBIDDEN_NAME", name: "resolve_ticket", arguments: {} },
        { caseId: "T03_FORBIDDEN_ARGUMENT", name: ALLOWED_TOOL, arguments: { ticketId: 9999 } },
        { caseId: "T04_EXECUTION_FAILURE", name: ALLOWED_TOOL, arguments: {}, fail: true }
    ];
    const results = cases.map(item => {
        // Case마다 새 Dispatcher를 만들므로 횟수는 다른 Case와 합산되지 않는다.
        const dispatcher = createToolDispatcher({
            currentTicketId: 7,
            findCurrentTicket: id => {
                if (item.fail) throw new Error("SYNTHETIC_TOOL_FAILURE");
                return { id, status: "OPEN" };
            }
        });
        const result = dispatcher.dispatch(JSON.stringify({ name: item.name, arguments: item.arguments }));
        return { caseId: item.caseId, code: result.code, toolExecuted: result.toolExecuted,
            toolExecutions: dispatcher.getExecutionCount() };
    });
    return {
        evidence: "OFFLINE_FAKE_TOOL_EXECUTION",
        providerCalls: 0,
        databaseOperations: 0,
        ticketMutations: 0,
        results
    };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const args = process.argv.slice(2);
    if (args.length === 1 && args[0] === "--run-fake-tools") {
        console.log(JSON.stringify(runFakeToolSpike(), null, 2));
    } else {
        console.error("FAKE_TOOLS_ONLY: --run-fake-tools만 지원합니다. 실제 AI·DB는 호출하지 않습니다.");
        process.exitCode = 1;
    }
}
