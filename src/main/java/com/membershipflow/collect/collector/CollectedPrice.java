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
        String         sourceName,
        String         sourceKey // course_source_mapping upsert용 (#144). 컬렉터가 못 채우면 null
) {
    // sourceKey를 채울 수 없는 컬렉터(동부/시세닷컴)를 위한 하위 호환 생성자
    public CollectedPrice(String courseName, String region, CourseType courseType,
                           MembershipType membershipType, Integer holes, long price, String sourceName) {
        this(courseName, region, courseType, membershipType, holes, price, sourceName, null);
    }
}
