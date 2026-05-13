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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.jpetstore.domain.LineItem;
import org.mybatis.jpetstore.domain.Order;
import org.mybatis.jpetstore.mapper.ItemMapper;
import org.mybatis.jpetstore.mapper.LineItemMapper;
import org.mybatis.jpetstore.mapper.OrderMapper;
import org.mybatis.jpetstore.mapper.SequenceMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Characterization tests for {@link OrderService} (WO-002).
 * <p>
 * Pins down the current behavior of the order pipeline against the real HSQLDB seed data so the N+1 query optimization
 * and the upcoming Spring Boot / Jakarta EE migration cannot silently regress: sequential order ID generation from the
 * {@code SEQUENCE} table, inventory decrement per line item, transactional atomicity on a partial-insert failure,
 * end-to-end {@code getOrder()} hydration (header + line items + item details + inventory), and the exact 2 + 2N SQL
 * fan-out for an N-line-item order.
 * <p>
 * Uses a dedicated {@link OrderServiceTestContext} (rather than the shared {@code MapperTestContext}) so the
 * {@link QueryCountInterceptor} plugin is scoped to this test and does not perturb the rest of the suite.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderServiceCharacterizationTest.OrderServiceTestContext.class)
@Transactional
class OrderServiceCharacterizationTest {

  private static final String ITEM_1 = "EST-1";
  private static final String ITEM_2 = "EST-2";
  private static final String ITEM_3 = "EST-3";
  private static final int SEED_INVENTORY_QTY = 10000;
  private static final int SEED_ORDERNUM_START = 1000;

  @Autowired
  private ItemMapper itemMapper;
  @Autowired
  private OrderMapper orderMapper;
  @Autowired
  private SequenceMapper sequenceMapper;
  @Autowired
  private LineItemMapper lineItemMapper;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private PlatformTransactionManager transactionManager;
  @Autowired
  private QueryCountInterceptor queryCounter;

  private OrderService orderService() {
    return new OrderService(itemMapper, orderMapper, sequenceMapper, lineItemMapper);
  }

  @Test
  void insertOrderGeneratesSequentialOrderIdsStartingAt1000() {
    Order first = sampleOrder("j2ee");
    first.getLineItems().add(lineItem(1, ITEM_1, 1));
    Order second = sampleOrder("j2ee");
    second.getLineItems().add(lineItem(1, ITEM_2, 1));

    orderService().insertOrder(first);
    orderService().insertOrder(second);

    assertThat(first.getOrderId()).isEqualTo(SEED_ORDERNUM_START);
    assertThat(second.getOrderId()).isEqualTo(SEED_ORDERNUM_START + 1);

    Integer sequenceAfter = jdbcTemplate.queryForObject("SELECT NEXTID FROM SEQUENCE WHERE NAME = 'ordernum'",
        Integer.class);
    assertThat(sequenceAfter).isEqualTo(SEED_ORDERNUM_START + 2);
  }

  @Test
  void insertOrderDecrementsInventoryQuantityForEachLineItem() {
    Order order = sampleOrder("j2ee");
    order.getLineItems().add(lineItem(1, ITEM_1, 3));
    order.getLineItems().add(lineItem(2, ITEM_2, 5));
    order.getLineItems().add(lineItem(3, ITEM_3, 7));

    orderService().insertOrder(order);

    assertThat(inventoryQty(ITEM_1)).isEqualTo(SEED_INVENTORY_QTY - 3);
    assertThat(inventoryQty(ITEM_2)).isEqualTo(SEED_INVENTORY_QTY - 5);
    assertThat(inventoryQty(ITEM_3)).isEqualTo(SEED_INVENTORY_QTY - 7);
  }

  @Test
  void insertOrderIsAtomicAndRollsBackPartialInsertOnFailure() {
    // The failing call must run in a brand-new transaction so its rollback
    // happens at the database level immediately — otherwise the outer test
    // transaction would still see the partial writes until method teardown.
    TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    Order doomedOrder = sampleOrder("j2ee");
    doomedOrder.setOrderDate(null); // ORDERS.ORDERDATE is NOT NULL — insertOrder will fail.
    doomedOrder.getLineItems().add(lineItem(1, ITEM_1, 4));
    doomedOrder.getLineItems().add(lineItem(2, ITEM_2, 6));

    assertThatThrownBy(() -> requiresNew.executeWithoutResult(status -> orderService().insertOrder(doomedOrder)))
        .isInstanceOf(DataAccessException.class);

    // Sequence advancement must be rolled back along with the inventory decrement.
    Integer sequenceAfter = jdbcTemplate.queryForObject("SELECT NEXTID FROM SEQUENCE WHERE NAME = 'ordernum'",
        Integer.class);
    assertThat(sequenceAfter).isEqualTo(SEED_ORDERNUM_START);
    assertThat(inventoryQty(ITEM_1)).isEqualTo(SEED_INVENTORY_QTY);
    assertThat(inventoryQty(ITEM_2)).isEqualTo(SEED_INVENTORY_QTY);

    // No ORDERS, ORDERSTATUS, or LINEITEM rows should have been persisted.
    Integer orders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ORDERS", Integer.class);
    Integer lineItems = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM LINEITEM", Integer.class);
    assertThat(orders).isZero();
    assertThat(lineItems).isZero();
  }

  @Test
  void getOrderReturnsHeaderLineItemsItemDetailsAndInventoryQuantities() {
    Order inserted = sampleOrder("j2ee");
    inserted.getLineItems().add(lineItem(1, ITEM_1, 2));
    inserted.getLineItems().add(lineItem(2, ITEM_2, 3));
    inserted.getLineItems().add(lineItem(3, ITEM_3, 4));
    orderService().insertOrder(inserted);

    Order fetched = orderService().getOrder(inserted.getOrderId());

    assertThat(fetched).isNotNull();
    assertThat(fetched.getOrderId()).isEqualTo(inserted.getOrderId());
    assertThat(fetched.getUsername()).isEqualTo("j2ee");
    assertThat(fetched.getStatus()).isEqualTo("P");
    assertThat(fetched.getCourier()).isEqualTo("UPS");
    assertThat(fetched.getTotalPrice()).isEqualByComparingTo(new BigDecimal("99.99"));

    assertThat(fetched.getLineItems()).hasSize(3);
    LineItem firstLine = fetched.getLineItems().get(0);
    assertThat(firstLine.getOrderId()).isEqualTo(inserted.getOrderId());
    assertThat(firstLine.getLineNumber()).isEqualTo(1);
    assertThat(firstLine.getItemId()).isEqualTo(ITEM_1);
    assertThat(firstLine.getQuantity()).isEqualTo(2);
    assertThat(firstLine.getItem()).isNotNull();
    assertThat(firstLine.getItem().getItemId()).isEqualTo(ITEM_1);
    assertThat(firstLine.getItem().getQuantity()).isEqualTo(SEED_INVENTORY_QTY - 2);
    assertThat(firstLine.getItem().getProduct()).isNotNull();
    assertThat(firstLine.getItem().getProduct().getProductId()).isEqualTo("FI-SW-01");

    assertThat(fetched.getLineItems().get(1).getItem().getQuantity()).isEqualTo(SEED_INVENTORY_QTY - 3);
    assertThat(fetched.getLineItems().get(2).getItem().getQuantity()).isEqualTo(SEED_INVENTORY_QTY - 4);
  }

  @Test
  void getOrdersByUsernameReturnsInsertedOrders() {
    Order order = sampleOrder("ACID");
    order.getLineItems().add(lineItem(1, ITEM_1, 1));
    orderService().insertOrder(order);

    List<Order> orders = orderService().getOrdersByUsername("ACID");

    assertThat(orders).hasSize(1);
    assertThat(orders.get(0).getOrderId()).isEqualTo(order.getOrderId());
    assertThat(orders.get(0).getUsername()).isEqualTo("ACID");
    assertThat(orders.get(0).getStatus()).isEqualTo("P");
  }

  @Test
  void getOrderExecutesExactlyEightSqlQueriesForAThreeItemOrder() {
    Order order = sampleOrder("j2ee");
    order.getLineItems().add(lineItem(1, ITEM_1, 1));
    order.getLineItems().add(lineItem(2, ITEM_2, 1));
    order.getLineItems().add(lineItem(3, ITEM_3, 1));
    orderService().insertOrder(order);

    queryCounter.reset();
    Order fetched = orderService().getOrder(order.getOrderId());

    // 1 (orderMapper.getOrder header join) + 1 (lineItemMapper.getLineItemsByOrderId)
    // + 3 (itemMapper.getItem per line item) + 3 (itemMapper.getInventoryQuantity per line item) = 8.
    // This is the documented N+1 fan-out: 2 + 2*N for N line items.
    assertThat(queryCounter.getCount()).as("getOrder() should execute exactly 2 + 2*N SQL queries (N=3 → 8)")
        .isEqualTo(8);
    assertThat(fetched.getLineItems()).hasSize(3);
  }

  private int inventoryQty(String itemId) {
    return jdbcTemplate.queryForObject("SELECT QTY FROM INVENTORY WHERE ITEMID = ?", Integer.class, itemId);
  }

  private static Order sampleOrder(String username) {
    Order order = new Order();
    order.setUsername(username);
    order.setOrderDate(new Date());
    order.setShipAddress1("1 Main St");
    order.setShipAddress2(null);
    order.setShipCity("San Francisco");
    order.setShipState("CA");
    order.setShipZip("94000");
    order.setShipCountry("USA");
    order.setBillAddress1("1 Main St");
    order.setBillAddress2(null);
    order.setBillCity("San Francisco");
    order.setBillState("CA");
    order.setBillZip("94000");
    order.setBillCountry("USA");
    order.setCourier("UPS");
    order.setTotalPrice(new BigDecimal("99.99"));
    order.setBillToFirstName("First");
    order.setBillToLastName("Last");
    order.setShipToFirstName("First");
    order.setShipToLastName("Last");
    order.setCreditCard("4111 1111 1111 1111");
    order.setExpiryDate("12/30");
    order.setCardType("Visa");
    order.setLocale("US");
    order.setStatus("P");
    return order;
  }

  private static LineItem lineItem(int lineNumber, String itemId, int quantity) {
    LineItem li = new LineItem();
    li.setLineNumber(lineNumber);
    li.setItemId(itemId);
    li.setQuantity(quantity);
    li.setUnitPrice(new BigDecimal("9.99"));
    return li;
  }

  @Configuration
  @MapperScan("org.mybatis.jpetstore.mapper")
  static class OrderServiceTestContext {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.HSQL)
          .setScriptEncoding("UTF-8").ignoreFailedDrops(true).addScript("database/jpetstore-hsqldb-schema.sql")
          .addScripts("database/jpetstore-hsqldb-dataload.sql").build();
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return new DataSourceTransactionManager(dataSource());
    }

    @Bean
    QueryCountInterceptor queryCountInterceptor() {
      return new QueryCountInterceptor();
    }

    @Bean
    SqlSessionFactoryBean sqlSessionFactory(QueryCountInterceptor interceptor) {
      SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
      factoryBean.setDataSource(dataSource());
      factoryBean.setTypeAliasesPackage("org.mybatis.jpetstore.domain");
      factoryBean.setPlugins(interceptor);
      return factoryBean;
    }

    @Bean
    JdbcTemplate jdbcTemplate() {
      return new JdbcTemplate(dataSource());
    }

  }

}
