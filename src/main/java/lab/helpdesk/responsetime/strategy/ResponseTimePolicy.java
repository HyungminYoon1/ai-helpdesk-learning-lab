// 이 인터페이스는 “모든 응답 시간 정책은 목표 응답 시간을 제공해야 한다”라는 공통 계약
package lab.helpdesk.responsetime.strategy;

import java.time.Duration;

public interface ResponseTimePolicy {

    Duration targetResponseTime();
}
