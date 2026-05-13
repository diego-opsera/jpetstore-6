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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.jpetstore.domain.Category;
import org.mybatis.jpetstore.domain.Item;
import org.mybatis.jpetstore.domain.Product;
import org.mybatis.jpetstore.mapper.CategoryMapper;
import org.mybatis.jpetstore.mapper.ItemMapper;
import org.mybatis.jpetstore.mapper.MapperTestContext;
import org.mybatis.jpetstore.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterization tests for {@link CatalogService} against the real HSQLDB seed data (WO-003).
 * <p>
 * Pins down the category listing, product browse-by-category, item-by-product, keyword search, and stock-check behavior
 * before the Stripes → Spring MVC migration and the search deduplication fix. The {@code searchProductList} tests
 * intentionally document the current no-deduplication behavior across multi-keyword queries so the upcoming fix has a
 * baseline to diff against.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MapperTestContext.class)
@Transactional
class CatalogServiceCharacterizationTest {

  @Autowired
  private CategoryMapper categoryMapper;
  @Autowired
  private ItemMapper itemMapper;
  @Autowired
  private ProductMapper productMapper;

  private CatalogService catalogService() {
    return new CatalogService(categoryMapper, itemMapper, productMapper);
  }

  @Test
  void getCategoryListReturnsExactlyFiveCategoriesFromSeedData() {
    List<Category> categories = catalogService().getCategoryList();

    assertThat(categories).hasSize(5);
    assertThat(categories).extracting(Category::getCategoryId).containsExactlyInAnyOrder("FISH", "DOGS", "REPTILES",
        "CATS", "BIRDS");
  }

  @Test
  void getCategoryReturnsCategoryNameForKnownId() {
    Category category = catalogService().getCategory("DOGS");

    assertThat(category).isNotNull();
    assertThat(category.getCategoryId()).isEqualTo("DOGS");
    assertThat(category.getName()).isEqualTo("Dogs");
  }

  @Test
  void searchProductListFishDogReturnsCombinedResultsFromBothKeywords() {
    // The current implementation issues one SQL query per keyword and concatenates the
    // raw results. For "fish dog" against the seed: "fish" matches "Angelfish" and "Goldfish"
    // (PRODUCT.NAME LIKE '%fish%'); "dog" matches "Bulldog". No product name contains both
    // keywords, so this particular input doesn't produce duplicates — but the surface area
    // is still the no-deduplication search path.
    List<Product> results = catalogService().searchProductList("fish dog");

    assertThat(results).extracting(Product::getName).containsExactlyInAnyOrder("Angelfish", "Goldfish", "Bulldog");
  }

  @Test
  void searchProductListDoesNotDeduplicateAcrossKeywords() {
    // "Goldfish" matches both keywords ("fish" via suffix, "gold" via prefix), so the
    // current no-dedup implementation returns it twice. "Golden Retriever" also matches
    // "gold". The test pins down the count of each name (HSQLDB row order is not stable
    // across runs, so we don't assert the exact sequence) — any change in the no-dedup
    // fan-out behavior surfaces here.
    List<Product> results = catalogService().searchProductList("fish gold");

    assertThat(results).hasSize(4);
    assertThat(results).extracting(Product::getName).containsExactlyInAnyOrder("Angelfish", "Goldfish", "Goldfish",
        "Golden Retriever");
    assertThat(results.stream().filter(p -> "Goldfish".equals(p.getName())).count())
        .as("Goldfish must appear twice — proves the no-dedup fan-out").isEqualTo(2);
  }

  @Test
  void searchProductListIsCaseInsensitive() {
    List<Product> upper = catalogService().searchProductList("FISH");
    List<Product> lower = catalogService().searchProductList("fish");

    assertThat(upper).hasSize(2);
    assertThat(lower).hasSize(2);
    assertThat(upper).extracting(Product::getName)
        .containsExactlyInAnyOrderElementsOf(lower.stream().map(Product::getName).toList());
  }

  @Test
  void getProductListByCategoryReturnsExpectedFishProducts() {
    List<Product> products = catalogService().getProductListByCategory("FISH");

    assertThat(products).extracting(Product::getName).containsExactlyInAnyOrder("Angelfish", "Tiger Shark", "Koi",
        "Goldfish");
  }

  @Test
  void getItemListByProductReturnsItemsLinkedToProduct() {
    List<Item> items = catalogService().getItemListByProduct("FI-SW-01");

    assertThat(items).isNotEmpty();
    assertThat(items).allMatch(item -> "FI-SW-01".equals(item.getProduct().getProductId()));
  }

  @Test
  void getItemReturnsItemWithProductAndInventoryQuantity() {
    Item item = catalogService().getItem("EST-1");

    assertThat(item).isNotNull();
    assertThat(item.getItemId()).isEqualTo("EST-1");
    assertThat(item.getProduct()).isNotNull();
    assertThat(item.getProduct().getProductId()).isEqualTo("FI-SW-01");
    assertThat(item.getQuantity()).isEqualTo(10000);
  }

  @Test
  void isItemInStockTrueForSeededItem() {
    assertThat(catalogService().isItemInStock("EST-1")).isTrue();
  }

}
