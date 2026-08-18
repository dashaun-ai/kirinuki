package ai.dashaun.kirinuki;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import ai.dashaun.kirinuki.review.ClipReviewRepository;
import ai.dashaun.kirinuki.video.VideoRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        postgres.start();
    }

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected VideoRepository videoRepository;

    @Autowired
    protected ClipReviewRepository clipReviewRepository;

    @BeforeEach
    void clearDatabase() {
        clipReviewRepository.deleteAll();
        videoRepository.deleteAll();
    }
}
