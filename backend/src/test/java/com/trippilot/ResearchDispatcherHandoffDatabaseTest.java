package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;
import com.trippilot.config.TripPilotProperties;
import com.trippilot.research.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@EnabledIfEnvironmentVariable(named="PHASE1_DB_TESTS",matches="true")
@SpringBootTest(properties="trippilot.dispatch.enabled=false")
class ResearchDispatcherHandoffDatabaseTest {
    @Autowired DataSource dataSource;
    @Autowired TripPilotProperties properties;
    @Test void standbyDoesNotRecoverOrClaimUntilPreviousOwnerReleasesRealMysqlLock() throws Exception {
        var firstRecovery=mock(RestartRecovery.class);var nextRecovery=mock(RestartRecovery.class);
        var firstRepository=mock(ResearchRepository.class);var nextRepository=mock(ResearchRepository.class);
        var executor=mock(ResearchExecutor.class);
        var first=new ResearchDispatcher(dataSource,firstRepository,executor,firstRecovery,properties);
        var next=new ResearchDispatcher(dataSource,nextRepository,executor,nextRecovery,properties,true);
        try {
            first.start();next.start();
            Thread.sleep(1500);
            verify(firstRecovery,times(1)).interruptOrphanedRuns();
            verifyNoInteractions(nextRecovery,nextRepository,executor);
            first.stop();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(mockingDetails(nextRecovery).getInvocations().isEmpty() && System.nanoTime()<deadline) Thread.sleep(100);
            verify(nextRecovery,times(1)).interruptOrphanedRuns();
            verify(nextRepository,timeout(3000).atLeastOnce()).claim(any());
            next.stop();
            var replacement=new ResearchDispatcher(dataSource,mock(ResearchRepository.class),executor,mock(RestartRecovery.class),properties);
            try { assertDoesNotThrow(replacement::start); } finally { replacement.stop(); }
        } finally { first.stop();next.stop(); }
    }
}
