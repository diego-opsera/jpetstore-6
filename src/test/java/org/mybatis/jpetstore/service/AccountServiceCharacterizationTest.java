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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.jpetstore.domain.Account;
import org.mybatis.jpetstore.mapper.AccountMapper;
import org.mybatis.jpetstore.mapper.MapperTestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterization tests for the account authentication flow (WO-001).
 * <p>
 * These tests pin down the exact current behavior of {@link AccountService} executed against the real HSQLDB seed data.
 * They form the regression baseline that protects upcoming security hardening (BCrypt password hashing) and the Spring
 * Boot / Jakarta EE framework migration. They intentionally exercise the production SQL — including the plaintext
 * password comparison in {@code AccountMapper.getAccountByUsernameAndPassword} — so any change to that contract
 * surfaces here first.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MapperTestContext.class)
@Transactional
class AccountServiceCharacterizationTest {

  @Autowired
  private AccountMapper accountMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private AccountService accountService() {
    return new AccountService(accountMapper);
  }

  @Test
  void getAccountWithValidCredentialsReturnsAccountWithUsernameAndPasswordNulledOut() {
    Account account = accountService().getAccount("j2ee", "j2ee");

    assertThat(account).isNotNull();
    assertThat(account.getUsername()).isEqualTo("j2ee");
    // The mapper's projection does not select the PASSWORD column, so the
    // returned Account.password is null even when the credentials matched.
    assertThat(account.getPassword()).isNull();
    assertThat(account.getFavouriteCategoryId()).isEqualTo("DOGS");
  }

  @Test
  void getAccountWithAcidUserReturnsAccount() {
    Account account = accountService().getAccount("ACID", "ACID");

    assertThat(account).isNotNull();
    assertThat(account.getUsername()).isEqualTo("ACID");
    assertThat(account.getPassword()).isNull();
    assertThat(account.getFavouriteCategoryId()).isEqualTo("CATS");
  }

  @Test
  void getAccountWithWrongPasswordReturnsNull() {
    Account account = accountService().getAccount("j2ee", "wrongpassword");

    assertThat(account).isNull();
  }

  @Test
  void getAccountWithUnknownUsernameReturnsNull() {
    Account account = accountService().getAccount("does-not-exist", "whatever");

    assertThat(account).isNull();
  }

  @Test
  void insertAccountPersistsRecordRetrievableByCredentials() {
    Account account = newAccount("newuser", "secret", "DOGS");

    accountService().insertAccount(account);

    Account retrieved = accountService().getAccount("newuser", "secret");
    assertThat(retrieved).isNotNull();
    assertThat(retrieved.getUsername()).isEqualTo("newuser");
    assertThat(retrieved.getEmail()).isEqualTo("newuser@example.com");
    assertThat(retrieved.getFavouriteCategoryId()).isEqualTo("DOGS");
    // Password column not projected — confirms parity with login-time behavior.
    assertThat(retrieved.getPassword()).isNull();

    Map<String, Object> signon = jdbcTemplate.queryForMap("SELECT * FROM signon WHERE username = ?", "newuser");
    assertThat(signon).containsEntry("USERNAME", "newuser").containsEntry("PASSWORD", "secret");
  }

  @Test
  void insertAccountFollowedByWrongPasswordReturnsNull() {
    accountService().insertAccount(newAccount("anotheruser", "rightpass", "FISH"));

    assertThat(accountService().getAccount("anotheruser", "wrongpass")).isNull();
  }

  @Test
  void updateAccountWithNonEmptyPasswordUpdatesSignonTable() {
    Account account = new Account();
    account.setUsername("j2ee");
    account.setEmail("yourname@yourdomain.com");
    account.setFirstName("ABC");
    account.setLastName("XYX");
    account.setStatus("OK");
    account.setAddress1("901 San Antonio Road");
    account.setAddress2("MS UCUP02-206");
    account.setCity("Palo Alto");
    account.setState("CA");
    account.setZip("94303");
    account.setCountry("USA");
    account.setPhone("555-555-5555");
    account.setLanguagePreference("english");
    account.setFavouriteCategoryId("DOGS");
    account.setListOption(true);
    account.setBannerOption(true);
    account.setPassword("newpassword");

    accountService().updateAccount(account);

    Map<String, Object> signon = jdbcTemplate.queryForMap("SELECT * FROM signon WHERE username = ?", "j2ee");
    assertThat(signon).containsEntry("USERNAME", "j2ee").containsEntry("PASSWORD", "newpassword");

    // The old password no longer authenticates; the new one does.
    assertThat(accountService().getAccount("j2ee", "j2ee")).isNull();
    assertThat(accountService().getAccount("j2ee", "newpassword")).isNotNull();
  }

  @Test
  void updateAccountWithEmptyPasswordDoesNotTouchSignonTable() {
    Account account = new Account();
    account.setUsername("j2ee");
    account.setEmail("yourname@yourdomain.com");
    account.setFirstName("ABC");
    account.setLastName("XYX");
    account.setStatus("OK");
    account.setAddress1("901 San Antonio Road");
    account.setAddress2("MS UCUP02-206");
    account.setCity("Palo Alto");
    account.setState("CA");
    account.setZip("94303");
    account.setCountry("USA");
    account.setPhone("555-555-5555");
    account.setLanguagePreference("english");
    account.setFavouriteCategoryId("DOGS");
    account.setListOption(true);
    account.setBannerOption(true);
    account.setPassword("");

    accountService().updateAccount(account);

    // The original seed password must still authenticate when password is left blank.
    assertThat(accountService().getAccount("j2ee", "j2ee")).isNotNull();
  }

  @Test
  void getAccountByUsernameReturnsFullProfileForJ2eeUser() {
    Account account = accountService().getAccount("j2ee");

    assertThat(account).isNotNull();
    assertThat(account.getUsername()).isEqualTo("j2ee");
    assertThat(account.getEmail()).isEqualTo("yourname@yourdomain.com");
    assertThat(account.getFavouriteCategoryId()).isEqualTo("DOGS");
    assertThat(account.getLanguagePreference()).isEqualTo("english");
    assertThat(account.isListOption()).isTrue();
    assertThat(account.isBannerOption()).isTrue();
    assertThat(account.getBannerName()).isEqualTo("<image src=\"../images/banner_dogs.gif\">");
  }

  private static Account newAccount(String username, String password, String favouriteCategory) {
    Account account = new Account();
    account.setUsername(username);
    account.setPassword(password);
    account.setEmail(username + "@example.com");
    account.setFirstName("First");
    account.setLastName("Last");
    account.setStatus("OK");
    account.setAddress1("1 Test St");
    account.setAddress2(null);
    account.setCity("Testville");
    account.setState("CA");
    account.setZip("94000");
    account.setCountry("USA");
    account.setPhone("555-000-0000");
    account.setLanguagePreference("english");
    account.setFavouriteCategoryId(favouriteCategory);
    account.setListOption(true);
    account.setBannerOption(true);
    return account;
  }

}
