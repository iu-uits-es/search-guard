/*
 * Copyright 2018 floragunn GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.floragunn.searchguard.compliance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.settings.Settings;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.floragunn.searchguard.resolver.IndexResolverReplacer;
import com.floragunn.searchguard.resolver.IndexResolverReplacer.Resolved;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.support.WildcardMatcher;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;


public final class ComplianceConfig {

    private final Logger log = LogManager.getLogger(getClass());
    private final Settings settings;
    private final Map<String, Set<String>> readEnabledFields = new HashMap<>(100);
    private final List<String> watchedWriteIndices;
    private DateTimeFormatter auditLogPattern = null;
    private String auditLogIndex = null;
    private final boolean logDiffsOnlyForWrite;
    private final boolean logMetadataOnly;
    private final boolean logExternalConfig;
    private final boolean logInternalConfig;
    private final LoadingCache<String, Set<String>> cache;
    private final Set<String> immutableIndicesPatterns;
    private final byte[] salt16;
    private final String searchguardIndex;
    private final IndexResolverReplacer irr;

    public ComplianceConfig(final Settings settings, final IndexResolverReplacer irr) {
        super();
        this.settings = settings;
        this.irr = irr;
        final List<String> watchedReadFields = this.settings.getAsList(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_READ_WATCHED_FIELDS,
                Collections.emptyList(), false);

        watchedWriteIndices = settings.getAsList(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_WRITE_WATCHED_INDICES, Collections.emptyList());
        logDiffsOnlyForWrite = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_WRITE_DIFFS_ONLY, false);
        logMetadataOnly = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_METADATA_ONLY, false);
        logExternalConfig = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_EXTERNAL_CONFIG_ENABLED, true);
        logInternalConfig = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_COMPLIANCE_HISTORY_INTERNAL_CONFIG_ENABLED, true);
        immutableIndicesPatterns = new HashSet<String>(settings.getAsList(ConfigConstants.SEARCHGUARD_COMPLIANCE_IMMUTABLE_INDICES, Collections.emptyList()));
        final String saltAsString = settings.get(ConfigConstants.SEARCHGUARD_COMPLIANCE_SALT, ConfigConstants.SEARCHGUARD_COMPLIANCE_SALT_DEAULT);
        final byte[] saltAsBytes = saltAsString.getBytes(StandardCharsets.UTF_8);

        
        if(saltAsBytes.length < 16) {
            throw new ElasticsearchException(ConfigConstants.SEARCHGUARD_COMPLIANCE_SALT+" must be at least contain 16 bytes");
        }
        
        salt16 = Arrays.copyOf(saltAsBytes, 16);
        this.searchguardIndex = settings.get(ConfigConstants.SEARCHGUARD_CONFIG_INDEX_NAME, ConfigConstants.SG_DEFAULT_CONFIG_INDEX);
        
        //searchguard.compliance.pii_fields:
        //  - indexpattern,fieldpattern,fieldpattern,....
        for(String watchedReadField: watchedReadFields) {
            final List<String> split = new ArrayList<>(Arrays.asList(watchedReadField.split(",")));
            if(split.isEmpty()) {
                continue;
            } else if(split.size() == 1) {
                readEnabledFields.put(split.get(0), Collections.singleton("*"));
            } else {
                Set<String> _fields = new HashSet<String>(split.subList(1, split.size()));
                readEnabledFields.put(split.get(0), _fields);
            }
        }

        final String type = settings.get(ConfigConstants.SEARCHGUARD_AUDIT_TYPE_DEFAULT, null);
        if("internal_elasticsearch".equalsIgnoreCase(type)) {
            final String index = settings.get(ConfigConstants.SEARCHGUARD_AUDIT_CONFIG_DEFAULT_PREFIX + ConfigConstants.SEARCHGUARD_AUDIT_ES_INDEX,"'sg6-auditlog-'YYYY.MM.dd");
            try {
                auditLogPattern = DateTimeFormat.forPattern(index); //throws IllegalArgumentException if no pattern
            } catch (IllegalArgumentException e) {
                //no pattern
                auditLogIndex = index;
            } catch (Exception e) {
                log.error("Unable to check if auditlog index {} is part of compliance setup", index, e);
            }
        }

        log.info("PII configuration [auditLogPattern={},  auditLogIndex={}]: {}", auditLogPattern, auditLogIndex, readEnabledFields);


        cache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .build(new CacheLoader<String, Set<String>>() {
                    @Override
                    public Set<String> load(String index) throws Exception {
                        return getFieldsForIndex0(index);
                    }
                });
    }

    //cached
    @SuppressWarnings("unchecked")
    private Set<String> getFieldsForIndex0(String index) {

        if(index == null) {
            return Collections.EMPTY_SET;
        }

        if(auditLogIndex != null && auditLogIndex.equalsIgnoreCase(index)) {
            return Collections.EMPTY_SET;
        }

        if(auditLogPattern != null) {
            if(index.equalsIgnoreCase(getExpandedIndexName(auditLogPattern, null))) {
                return Collections.EMPTY_SET;
            }
        }

        final Set<String> tmp = new HashSet<String>(100);
        for(String indexPattern: readEnabledFields.keySet()) {
            if(indexPattern != null && !indexPattern.isEmpty() && WildcardMatcher.match(indexPattern, index)) {
                tmp.addAll(readEnabledFields.get(indexPattern));
            }
        }
        return tmp;
    }

    private String getExpandedIndexName(DateTimeFormatter indexPattern, String index) {
        if(indexPattern == null) {
            return index;
        }
        return indexPattern.print(DateTime.now(DateTimeZone.UTC));
    }

    public boolean writeHistoryEnabledForIndex(String index) {

        if(index == null) {
            return false;
        }
        
        if(searchguardIndex.equals(index)) {
            return logInternalConfig;
        }

        if(auditLogIndex != null && auditLogIndex.equalsIgnoreCase(index)) {
            return false;
        }

        if(auditLogPattern != null) {
            if(index.equalsIgnoreCase(getExpandedIndexName(auditLogPattern, null))) {
                return false;
            }
        }

        return WildcardMatcher.matchAny(watchedWriteIndices, index);
    }

    //no patterns here as parameters
    public boolean readHistoryEnabledForIndex(String index) {
        
        if(searchguardIndex.equals(index)) {
            return logInternalConfig;
        }
        
        try {
            return !cache.get(index).isEmpty();
        } catch (ExecutionException e) {
            log.error(e);
            return true;
        }
    }

    //no patterns here as parameters
    public boolean readHistoryEnabledForField(String index, String field) {
        
        if(searchguardIndex.equals(index)) {
            return logInternalConfig;
        }
        
        try {
            final Set<String> fields = cache.get(index);
            if(fields.isEmpty()) {
                return false;
            }

            return WildcardMatcher.matchAny(fields, field);
        } catch (ExecutionException e) {
            log.error(e);
            return true;
        }
    }

    public boolean logDiffsOnlyForWrite() {
        return logDiffsOnlyForWrite;
    }

    public boolean logMetadataOnly() {
        return logMetadataOnly;
    }

    public boolean logExternalConfig() {
        return logExternalConfig;
    }

    public boolean isIndexImmutable(Object request) {
        
        if(immutableIndicesPatterns.isEmpty()) {
            return false;
        }
        
        final Resolved resolved = irr.resolve(request);
        final Set<String> allIndices = resolved.getAllIndices();
        
        //assert allIndices.size() == 1:"only one index here, not "+allIndices;
        //assert allIndices.contains("_all"):"no _all in "+allIndices;
        //assert allIndices.contains("*"):"no * in "+allIndices;
        //assert allIndices.contains(""):"no EMPTY in "+allIndices;

        return WildcardMatcher.matchAny(immutableIndicesPatterns, allIndices);
    }

    public byte[] getSalt16() {
        return salt16.clone();
    }
}
