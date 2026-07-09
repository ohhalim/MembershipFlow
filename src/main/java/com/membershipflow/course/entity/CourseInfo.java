package com.membershipflow.course.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 골프장 부가정보 (#141) — 동아 상세페이지에서 수집.
 * membership_course와 1:1, course_id 기준 upsert.
 */
@Entity
@Table(name = "course_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false, unique = true)
    private Long courseId;

    @Column(length = 300)
    private String address;

    @Column(name = "membership_intro", columnDefinition = "TEXT")
    private String membershipIntro;

    @Column(name = "course_intro", columnDefinition = "TEXT")
    private String courseIntro;

    // 시세 흐름 + 향후 전망
    @Column(name = "price_outlook", columnDefinition = "TEXT")
    private String priceOutlook;

    // [{"grade","weekday","weekend"}] JSON 직렬화
    @Column(name = "green_fees", columnDefinition = "TEXT")
    private String greenFees;

    @Column(name = "caddie_fee", length = 200)
    private String caddieFee;

    @Column(name = "cart_fee", length = 200)
    private String cartFee;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public CourseInfo(Long courseId, String address, String membershipIntro, String courseIntro,
                      String priceOutlook, String greenFees, String caddieFee, String cartFee) {
        this.courseId        = courseId;
        this.createdAt       = LocalDateTime.now();
        update(address, membershipIntro, courseIntro, priceOutlook, greenFees, caddieFee, cartFee);
    }

    public void update(String address, String membershipIntro, String courseIntro,
                       String priceOutlook, String greenFees, String caddieFee, String cartFee) {
        this.address         = address;
        this.membershipIntro = membershipIntro;
        this.courseIntro     = courseIntro;
        this.priceOutlook    = priceOutlook;
        this.greenFees       = greenFees;
        this.caddieFee       = caddieFee;
        this.cartFee         = cartFee;
    }
}
