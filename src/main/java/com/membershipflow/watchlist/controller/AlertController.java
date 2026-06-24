package com.membershipflow.watchlist.controller;

import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.watchlist.dto.AlertResponse;
import com.membershipflow.watchlist.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public List<AlertResponse> list(@AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return alertService.getAlerts(principal.getMemberId());
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal OAuth2UserPrincipal principal,
                         @PathVariable Long id) {
        alertService.markRead(id, principal.getMemberId());
    }
}
