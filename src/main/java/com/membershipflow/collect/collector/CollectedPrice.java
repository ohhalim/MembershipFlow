package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;

public record CollectedPrice(
        String         courseName,
        String         region,
        CourseType     courseType,
        MembershipType membershipType,
        Integer        holes,   // null 허용 (동아는 목록 페이지에서 홀수 미제공)
        long           price,   // 원 단위 (만원×10000 변환 완료)
        String         sourceName
) {}
