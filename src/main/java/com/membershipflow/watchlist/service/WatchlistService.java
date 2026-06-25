package com.membershipflow.watchlist.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.price.repository.PriceHistoryRepository;
import com.membershipflow.watchlist.dto.WatchlistAddRequest;
import com.membershipflow.watchlist.dto.WatchlistResponse;
import com.membershipflow.watchlist.dto.WatchlistUpdateRequest;
import com.membershipflow.watchlist.entity.Watchlist;
import com.membershipflow.watchlist.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchlistService {

    private static final int FREE_TIER_LIMIT = 5;

    private final WatchlistRepository       watchlistRepository;
    private final MemberRepository          memberRepository;
    private final MembershipCourseRepository courseRepository;
    private final PriceHistoryRepository    priceHistoryRepository;

    @Transactional
    public WatchlistResponse add(Long memberId, WatchlistAddRequest req) {
        if (watchlistRepository.existsByMemberIdAndCourseId(memberId, req.courseId())) {
            throw new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS);
        }
        if (watchlistRepository.countByMemberId(memberId) >= FREE_TIER_LIMIT) {
            throw new BusinessException(ErrorCode.WATCHLIST_LIMIT_EXCEEDED);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        MembershipCourse course = courseRepository.findById(req.courseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_NOT_FOUND));

        Watchlist saved = watchlistRepository.save(
                Watchlist.builder()
                        .member(member)
                        .course(course)
                        .targetPrice(req.targetPrice())
                        .alertYn(req.alertYn())
                        .build());

        return WatchlistResponse.of(saved, null);
    }

    public List<WatchlistResponse> list(Long memberId) {
        List<Watchlist> items = watchlistRepository.findByMemberIdWithCourse(memberId);
        if (items.isEmpty()) return List.of();

        List<Long> courseIds = items.stream().map(w -> w.getCourse().getId()).toList();
        Map<Long, Long> latestPriceMap = priceHistoryRepository.findLatestByCourseIds(courseIds)
                .stream()
                .collect(Collectors.toMap(
                        ph -> ph.getCourse().getId(),
                        ph -> ph.getPrice(),
                        Math::min));

        return items.stream()
                .map(w -> WatchlistResponse.of(w, latestPriceMap.get(w.getCourse().getId())))
                .toList();
    }

    @Transactional
    public WatchlistResponse update(Long watchlistId, Long memberId, WatchlistUpdateRequest req) {
        Watchlist watchlist = getOwnedWatchlist(watchlistId, memberId);
        watchlist.update(req.targetPrice(), req.alertYn());
        Long courseId = watchlist.getCourse().getId();
        Long latestPrice = priceHistoryRepository.findLatestByCourseIds(List.of(courseId))
                .stream().findFirst().map(ph -> ph.getPrice()).orElse(null);
        return WatchlistResponse.of(watchlist, latestPrice);
    }

    @Transactional
    public void delete(Long watchlistId, Long memberId) {
        Watchlist watchlist = getOwnedWatchlist(watchlistId, memberId);
        watchlistRepository.delete(watchlist);
    }

    private Watchlist getOwnedWatchlist(Long watchlistId, Long memberId) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND));
        if (!watchlist.getMember().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
        return watchlist;
    }
}
