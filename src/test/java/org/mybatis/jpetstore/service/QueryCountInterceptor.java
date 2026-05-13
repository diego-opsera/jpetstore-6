/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.jpetstore.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * Test-only MyBatis plugin that counts the number of {@link Executor} query/update invocations.
 * <p>
 * Used by {@code OrderServiceCharacterizationTest} to document the exact SQL fan-out of {@code OrderService.getOrder()}
 * — the well-known N+1 pattern — without depending on log scraping. Each invocation of a mapper method (one round-trip
 * to the database, modulo L1/L2 cache hits) increments the counter by one.
 * <p>
 * The 4-arg {@code query} signature is the one mapper proxies actually invoke: {@code DefaultSqlSession.selectList}
 * calls {@code executor.query(ms, parameter, rowBounds, resultHandler)}. The CachingExecutor's internal delegation to
 * the 6-arg variant is a {@code this.} call and therefore bypasses the plugin proxy, so intercepting the 4-arg method
 * gives exactly one count per mapper select.
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class, RowBounds.class,
        ResultHandler.class }),
    @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }) })
public class QueryCountInterceptor implements Interceptor {

  private final AtomicInteger count = new AtomicInteger();

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    count.incrementAndGet();
    return invocation.proceed();
  }

  public int getCount() {
    return count.get();
  }

  public void reset() {
    count.set(0);
  }

}
