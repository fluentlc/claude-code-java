package ai.claude.code.web.controller;

import ai.claude.code.web.dto.ChatRequest;
import ai.claude.code.web.service.StreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

/**
 * SSE streaming endpoint.
 * POST /api/chat/stream — returns text/event-stream
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    @Autowired private StreamService streamService;
    @Autowired private ExecutorService streamExecutor;

    @PostMapping("/chat/stream")
    public SseEmitter stream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        streamExecutor.submit(() -> streamService.run(req, emitter));
        return emitter;
    }
}
