package com.sinhan.order.application;

import com.sinhan.order.infrastructure.OrderRepository;
import com.sinhan.order.infrastructure.OrderRepository.Job;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true")
public class OrderWorker {
  private static final Logger LOG = LoggerFactory.getLogger(OrderWorker.class);
  private final AtomicReference<Job> active = new AtomicReference<>();
  private final String owner = UUID.randomUUID().toString();
  private final OrderRepository repo;
  private final Workflow flow;

  public OrderWorker(OrderRepository r, Workflow f) {
    repo = r;
    flow = f;
  }

  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("order-work-");
    return scheduler;
  }

  @Scheduled(fixedDelay = 700)
  public void tick() {
    if (active.get() != null) return;
    Job job = repo.claim(owner);
    if (job == null) return;
    active.set(job);
    MDC.put("requestId", job.requestId());
    try {
      flow.run(job);
    } catch (Exception e) {
      LOG.error("workflow interrupted job={} errorType={}", job.id(), e.getClass().getSimpleName());
    } finally {
      MDC.remove("requestId");
      active.set(null);
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void heartbeat() {
    Job job = active.get();
    if (job != null) repo.renew(job);
  }
}
