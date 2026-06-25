package com.membershipflow.watchlist.controller;

import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.watchlist.dto.WatchlistAddRequest;
import com.membershipflow.watchlist.dto.WatchlistResponse;
import com.membershipflow.watchlist.dto.WatchlistUpdateRequest;
import com.membershipflow.watchlist.service.WatchlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse add(@AuthenticationPrincipal OAuth2UserPrincipal principal,
                                 @Valid @RequestBody WatchlistAddRequest req) {
        return watchlistService.add(principal.getMemberId(), req);
    }

    @GetMapping
    public List<WatchlistResponse> list(@AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return watchlistService.list(principal.getMemberId());
    }

    @PutMapping("/{id}")
    public WatchlistResponse update(@AuthenticationPrincipal OAuth2UserPrincipal principal,
                                    @PathVariable Long id,
                                    @Valid @RequestBody WatchlistUpdateRequest req) {
        return watchlistService.update(id, principal.getMemberId(), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal OAuth2UserPrincipal principal,
                       @PathVariable Long id) {
        watchlistService.delete(id, principal.getMemberId());
    }
}
