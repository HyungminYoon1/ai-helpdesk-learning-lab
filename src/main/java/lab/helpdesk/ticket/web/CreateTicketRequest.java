package lab.helpdesk.ticket.web;

import jakarta.validation.constraints.NotBlank;

// Client가 보낸 다음 JSON을 Java 객체로 받기 위한 데이터 그릇
public record CreateTicketRequest(
        @NotBlank(message = "title must not be blank")
        String title) {
}
