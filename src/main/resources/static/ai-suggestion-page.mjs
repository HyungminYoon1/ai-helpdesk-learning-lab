import { createAiSuggestionClient, createAiSuggestionView } from "./ai-suggestion-ui.mjs";

const form = document.querySelector("#ai-query-form");
const idInput = document.querySelector("#ai-ticket-id-input");
const view = createAiSuggestionView({
    statusElement: document.querySelector("#ai-ui-status"),
    resultElement: document.querySelector("#ai-result"),
    suggestionElement: document.querySelector("#ai-suggestion"),
    ticketIdElement: document.querySelector("#ai-ticket-id"),
    jobStatusElement: document.querySelector("#ai-job-status"),
    failureCodeElement: document.querySelector("#ai-failure-code"),
    summaryElement: document.querySelector("#ai-summary"),
    categoriesElement: document.querySelector("#ai-categories"),
    priorityElement: document.querySelector("#ai-priority"),
    reviewStatusElement: document.querySelector("#ai-review-status")
});
const client = createAiSuggestionClient({ fetchImpl: window.fetch.bind(window), view });

form.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.readSuggestion(Number(idInput.value));
});
