/*
 * SonarQube LDAP Plugin
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.ldap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.ldap.LdapAutodiscovery.LdapSrvRecord;

/**
 * The LdapSettingsManager will parse the settings.
 * This class is also responsible to cope with multiple ldap servers.
 */
@ServerSide
public class LdapSettingsManager {

  public static final String DEFAULT_LDAP_SERVER_KEY = "<default>";
  private static final Logger LOG = Loggers.get(LdapSettingsManager.class);

  private final LdapSettings settings;
  private final LdapAutodiscovery ldapAutodiscovery;
  private Map<String, LdapUserMapping> userMappings = null;
  private Map<String, LdapGroupMapping> groupMappings = null;
  private Map<String, LdapContextFactory> contextFactories;

  /**
   * Create an instance of the settings manager.
   *
   * @param settings The settings to use.
   */
  public LdapSettingsManager(LdapSettings settings, LdapAutodiscovery ldapAutodiscovery) {
    this.settings = settings;
    this.ldapAutodiscovery = ldapAutodiscovery;
  }

  /**
   * Get all the @link{LdapUserMapping}s available in the settings.
   *
   * @return A @link{Map} with all the @link{LdapUserMapping} objects.
   *         The key is the server key used in the settings (ldap for old single server notation).
   */
  public Map<String, LdapUserMapping> getUserMappings() {
    if (userMappings == null) {
      // Use linked hash map to preserve order
      userMappings = new LinkedHashMap<>();

      if (this.getContextFactories().size() > 0) {
        for (String serverKey : contextFactories.keySet()) {
          String settingsKey = getSettingsKey(serverKey);
          LOG.info("Setting key {}", settingsKey);

          LdapUserMapping userMapping = new LdapUserMapping(settings, settingsKey);
          if (StringUtils.isNotBlank(userMapping.getBaseDn())) {
            if (isConfigurationSimpleLdapConfig()) {
              LOG.info("User mapping : {}", userMapping);
            } else {
              LOG.info("User mapping for server {}: {}", serverKey, userMapping);
            }
            userMappings.put(serverKey, userMapping);
          } else {
            if (isConfigurationSimpleLdapConfig()) {
              LOG.info("Users will not be synchronized, because property '{}.user.baseDn' is empty.", settingsKey);
            } else {
              LOG.info("Users will not be synchronized for server {}, because property '{}.user.baseDn' is empty", serverKey, settingsKey);
            }
          }
        }
      }
    }
    return userMappings;
  }

  /**
   * Get all the @link{LdapGroupMapping}s available in the settings.
   *
   * @return A @link{Map} with all the @link{LdapGroupMapping} objects.
   * The key is the server key used in the settings (ldap for old single server notation).
   */
  public Map<String, LdapGroupMapping> getGroupMappings() {
    if (groupMappings == null) {
      // Use linked hash map to preserve order
      groupMappings = new LinkedHashMap<>();
      if (this.getContextFactories().size() > 0) {
        for (String serverKey : contextFactories.keySet()) {
          String settingsKey = getSettingsKey(serverKey);
          LOG.info("Setting key {}", settingsKey);

          LdapGroupMapping groupMapping = new LdapGroupMapping(settings, settingsKey);
          if (StringUtils.isNotBlank(groupMapping.getBaseDn())) {
            if (isConfigurationSimpleLdapConfig()) {
              LOG.info("Group mapping: {}", groupMapping);
            } else {
              LOG.info("Group mapping for server {}: {}", serverKey, groupMapping);
            }
            groupMappings.put(serverKey, groupMapping);
          } else {
            if (isConfigurationSimpleLdapConfig()) {
              LOG.info("Groups will not be synchronized, because property '{}.group.baseDn' is empty.", settingsKey);
            } else {
              LOG.info("Groups will not be synchronized for server {}, because property '{}.group.baseDn' is empty.", serverKey, settingsKey);
            }
          }
        }
      }
    }
    return groupMappings;
  }

  /**
   * Get all the @link{LdapContextFactory}s available in the settings.
   *
   * @return A @link{Map} with all the @link{LdapContextFactory} objects.
   * The key is the server key used in the settings (ldap for old single server notation).
   */
  public Map<String, LdapContextFactory> getContextFactories() {
    if (contextFactories == null) {
      // Use linked hash map to preserve order
      contextFactories = new LinkedHashMap<>();
      if (isConfigurationSimpleLdapConfig()) {
        initSimpleLdapConfiguration();
      } else {
        initMultiLdapConfiguration();
      }
    }
    return contextFactories;
  }

  private void initSimpleLdapConfiguration() {
    String realm = settings.getLdapRealm(LdapSettings.LDAP_PROPERTY_PREFIX);
    String ldapUrl = settings.getLdapUrl(LdapSettings.LDAP_PROPERTY_PREFIX);
    if (ldapUrl == null && realm != null) {
      LOG.info("Auto discovery mode");
      List<LdapSrvRecord> ldapServers = ldapAutodiscovery.getLdapServers(realm);
      if (ldapServers.isEmpty()) {
        throw new SonarException(
          String.format("The property '%s' is empty and SonarQube is not able to auto-discover any LDAP server.",
            settings.getLdapUrlKey(LdapSettings.LDAP_PROPERTY_PREFIX)));
      }
      int index = 1;
      for (LdapSrvRecord ldapSrvRecord : ldapServers) {
        if (StringUtils.isNotBlank(ldapSrvRecord.getServerUrl())) {
          LOG.info("Detected server: {}", ldapSrvRecord.getServerUrl());
          LdapContextFactory contextFactory = new LdapContextFactory(settings,
            LdapSettings.LDAP_PROPERTY_PREFIX, ldapSrvRecord.getServerUrl());
          contextFactories.put(DEFAULT_LDAP_SERVER_KEY + index, contextFactory);
          index++;
        }
      }
    } else {
      if (StringUtils.isBlank(ldapUrl)) {
        throw new SonarException(
          String.format("The property '%s' is empty and no realm configured to try auto-discovery.",
            settings.getLdapUrlKey(LdapSettings.LDAP_PROPERTY_PREFIX)));
      }
      LdapContextFactory contextFactory = new LdapContextFactory(settings, LdapSettings.LDAP_PROPERTY_PREFIX, ldapUrl);
      contextFactories.put(DEFAULT_LDAP_SERVER_KEY, contextFactory);
    }
  }

  private void initMultiLdapConfiguration() {
    if (settings.hasKey("ldap.url") || settings.hasKey("ldap.realm")) {
      throw new SonarException("When defining multiple LDAP servers with the property '" + LdapSettings.LDAP_SERVERS_PROPERTY + "', "
        + "all LDAP properties must be linked to one of those servers. Please remove properties like 'ldap.url', 'ldap.realm', ...");
    }

    String[] serverKeys = settings.getLdapServerKeys();
    for (String serverKey : serverKeys) {
      String prefix = LdapSettings.LDAP_PROPERTY_PREFIX + "." + serverKey;
      String ldapUrl = settings.getLdapUrl(prefix);
      if (StringUtils.isBlank(ldapUrl)) {
        throw new SonarException(String.format("The property '%s' property is empty while it is mandatory.",
          settings.getLdapUrlKey(prefix)));
      }
      LdapContextFactory contextFactory = new LdapContextFactory(settings, prefix, ldapUrl);
      contextFactories.put(serverKey, contextFactory);
    }
  }

  private String getSettingsKey(String serverKey) {
    String settingsKey = LdapSettings.LDAP_PROPERTY_PREFIX + "." + serverKey;
    if (isConfigurationSimpleLdapConfig()) {
      settingsKey = LdapSettings.LDAP_PROPERTY_PREFIX;
    }
    return settingsKey;
  }

  private boolean isConfigurationSimpleLdapConfig() {
    String[] serverKeys = settings.getLdapServerKeys();
    return serverKeys.length == 0;
  }

}
