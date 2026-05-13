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
package org.mybatis.jpetstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link Cart} (WO-003).
 * <p>
 * Documents the dual data-structure invariant of {@code Cart}: the {@code synchronized Map<itemId, CartItem>} and the
 * unsynchronized {@code List<CartItem>} must stay in lockstep across {@code addItem}, {@code removeItemById}, and
 * subtotal calculation. Existing {@code CartTest} covers some of this implicitly; this file pins it down explicitly so
 * the Stripes → Spring MVC migration cannot silently break either side of the pair.
 */
class CartCharacterizationTest {

  @Test
  void addItemWithAlreadyPresentItemIncrementsQuantityInsteadOfAddingDuplicate() {
    Cart cart = new Cart();
    Item item = itemWithPrice("EST-1", new BigDecimal("2.05"));

    cart.addItem(item, true);
    cart.addItem(item, true);
    cart.addItem(item, true);

    assertThat(cart.getNumberOfItems()).isEqualTo(1);
    assertThat(cart.getCartItemList()).hasSize(1);
    assertThat(cart.containsItemId("EST-1")).isTrue();
    assertThat(cart.getCartItemList().get(0).getQuantity()).isEqualTo(3);
    assertThat(cart.getCartItemList().get(0).getItem()).isSameAs(item);
  }

  @Test
  void addItemRegistersTheCartItemInBothItemMapAndItemList() {
    Cart cart = new Cart();
    Item itemA = itemWithPrice("EST-1", new BigDecimal("2.05"));
    Item itemB = itemWithPrice("EST-2", new BigDecimal("3.00"));

    cart.addItem(itemA, true);
    cart.addItem(itemB, false);

    assertThat(cart.containsItemId("EST-1")).isTrue();
    assertThat(cart.containsItemId("EST-2")).isTrue();
    assertThat(cart.getCartItemList()).extracting(ci -> ci.getItem().getItemId()).containsExactly("EST-1", "EST-2");
    assertThat(cart.getCartItemList().get(0).isInStock()).isTrue();
    assertThat(cart.getCartItemList().get(1).isInStock()).isFalse();
  }

  @Test
  void removeItemByIdRemovesEntryFromBothItemListAndItemMap() {
    Cart cart = new Cart();
    Item itemA = itemWithPrice("EST-1", new BigDecimal("2.05"));
    Item itemB = itemWithPrice("EST-2", new BigDecimal("3.00"));
    cart.addItem(itemA, true);
    cart.addItem(itemB, true);

    Item removed = cart.removeItemById("EST-1");

    assertThat(removed).isSameAs(itemA);
    assertThat(cart.containsItemId("EST-1")).isFalse();
    assertThat(cart.containsItemId("EST-2")).isTrue();
    assertThat(cart.getNumberOfItems()).isEqualTo(1);
    assertThat(cart.getCartItemList()).extracting(ci -> ci.getItem().getItemId()).containsExactly("EST-2");
  }

  @Test
  void removeItemByIdForUnknownItemLeavesBothStructuresUnchanged() {
    Cart cart = new Cart();
    Item item = itemWithPrice("EST-1", new BigDecimal("2.05"));
    cart.addItem(item, true);

    Item removed = cart.removeItemById("UNKNOWN");

    assertThat(removed).isNull();
    assertThat(cart.containsItemId("EST-1")).isTrue();
    assertThat(cart.getCartItemList()).hasSize(1);
  }

  @Test
  void getSubTotalReturnsCorrectBigDecimalAcrossItemsWithDifferentPricesAndQuantities() {
    Cart cart = new Cart();
    Item itemA = itemWithPrice("EST-1", new BigDecimal("2.05"));
    Item itemB = itemWithPrice("EST-2", new BigDecimal("3.06"));
    Item itemC = itemWithPrice("EST-3", new BigDecimal("18.50"));

    cart.addItem(itemA, true);
    cart.setQuantityByItemId("EST-1", 5);
    cart.addItem(itemB, true);
    cart.setQuantityByItemId("EST-2", 6);
    cart.addItem(itemC, true);
    cart.setQuantityByItemId("EST-3", 2);

    // 2.05*5 = 10.25; 3.06*6 = 18.36; 18.50*2 = 37.00 — sum 65.61.
    assertThat(cart.getSubTotal()).isEqualByComparingTo(new BigDecimal("65.61"));
  }

  @Test
  void getSubTotalForEmptyCartIsZero() {
    assertThat(new Cart().getSubTotal()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private static Item itemWithPrice(String itemId, BigDecimal listPrice) {
    Item item = new Item();
    item.setItemId(itemId);
    item.setListPrice(listPrice);
    return item;
  }

}
