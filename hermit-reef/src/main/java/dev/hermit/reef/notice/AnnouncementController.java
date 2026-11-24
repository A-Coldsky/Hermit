package dev.hermit.reef.notice;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements")
public class AnnouncementController {

    private final AnnouncementService announcements;

    public AnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @GetMapping
    public ApiResponse<List<AnnouncementView>> list(HttpServletRequest request) {
        return ApiResponse.ok(announcements.list(), TraceIds.current(request));
    }

    @PostMapping
    public ApiResponse<AnnouncementView> create(@RequestBody AnnouncementRequest body, HttpServletRequest request) {
        if (body == null) {
            throw ApiException.invalidInput();
        }
        return ApiResponse.ok(
                announcements.create(actor(), body.title(), body.body(), body.level(),
                        Boolean.TRUE.equals(body.pinned())),
                TraceIds.current(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AnnouncementView> update(@PathVariable("id") long id, @RequestBody AnnouncementRequest body,
            HttpServletRequest request) {
        if (body == null) {
            throw ApiException.invalidInput();
        }
        return ApiResponse.ok(
                announcements.update(actor(), id, body.title(), body.body(), body.level(),
                        Boolean.TRUE.equals(body.pinned())),
                TraceIds.current(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<DeletedAnnouncement> delete(@PathVariable("id") long id, HttpServletRequest request) {
        announcements.delete(actor(), id);
        return ApiResponse.ok(new DeletedAnnouncement(id), TraceIds.current(request));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return authentication.getName();
    }

    public record AnnouncementRequest(String title, String body, String level, Boolean pinned) {
    }

    public record DeletedAnnouncement(long id) {
    }
}
