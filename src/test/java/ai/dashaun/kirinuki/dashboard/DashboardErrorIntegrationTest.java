package ai.dashaun.kirinuki.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import ai.dashaun.kirinuki.AbstractIntegrationTest;

class DashboardErrorIntegrationTest extends AbstractIntegrationTest {

    @Test
    void should_render_an_html_page_when_a_dashboard_request_fails() {
        String body = client.get().uri("/videos/{videoId}/dashboard", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("Video not found");
    }

    @Test
    void should_still_return_problem_details_when_an_api_request_fails() {
        ProblemDetail problemDetail = client.get().uri("/videos/{videoId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getTitle()).isEqualTo("Video not found");
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
}
