package lab.helpdesk.ai.web;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Static Page 조립 검증이다. Browser 실행·PostgreSQL 조회·실제 AI 호출 Test가 아니다.
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("in-memory")
class AiSuggestionPageIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    void static_page_provides_an_empty_agent_query_form_not_private_data() throws Exception {
        mvc.perform(get("/ai-suggestions.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"ai-query-form\"")))
                .andExpect(content().string(containsString("/ai-suggestion-page.mjs")))
                .andExpect(result -> assertThat(result.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                        .contains("AI 제안 — 담당자 검토 전"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ai-suggestion-page.mjs", "/ai-suggestion-ui.mjs"})
    void static_modules_are_served_from_the_application(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(containsString("createAiSuggestionClient")));
    }

    @Test
    void ticket_page_links_to_the_separate_agent_query_page() throws Exception {
        mvc.perform(get("/tickets.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/ai-suggestions.html\"")));
    }

    @Test
    void public_static_page_does_not_make_the_query_api_public() throws Exception {
        var result = mvc.perform(get("/api/tickets/7/ai-suggestion"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();
        assertThat(result.getHandler()).isNull();
    }
}
