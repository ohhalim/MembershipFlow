package com.membershipflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MembershipFlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(MembershipFlowApplication.class, args);
	}

}
