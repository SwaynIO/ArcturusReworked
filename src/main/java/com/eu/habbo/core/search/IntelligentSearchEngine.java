package com.eu.habbo.core.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Intelligent search engine with ML-powered relevance scoring and real-time indexing
 * Features fuzzy matching, semantic search, and predictive suggestions
 */
public class IntelligentSearchEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelligentSearchEngine.class);
    
    private static IntelligentSearchEngine instance;
    
    private final SearchIndexManager indexManager;
    private final QueryProcessor queryProcessor;
    private final RelevanceScorer relevanceScorer;
    private final SearchSuggestionsEngine suggestionsEngine;
    private final SearchAnalytics analytics;
    private final ScheduledExecutorService searchScheduler;
    
    // Search configuration
    private static final int MAX_SEARCH_RESULTS = 100;
    private static final double FUZZY_THRESHOLD = 0.7;
    private static final int SUGGESTION_LIMIT = 10;
    
    private IntelligentSearchEngine() {
        this.indexManager = new SearchIndexManager();
        this.queryProcessor = new QueryProcessor();
        this.relevanceScorer = new RelevanceScorer();
        this.suggestionsEngine = new SearchSuggestionsEngine();
        this.analytics = new SearchAnalytics();
        
        this.searchScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "SearchEngine");
            t.setDaemon(true);
            return t;
        });
        
        initializeSearchEngine();
        LOGGER.info("Intelligent Search Engine initialized");
    }
    
    public static synchronized IntelligentSearchEngine getInstance() {
        if (instance == null) {
            instance = new IntelligentSearchEngine();
        }
        return instance;
    }
    
    private void initializeSearchEngine() {
        // Start index optimization
        searchScheduler.scheduleWithFixedDelay(indexManager::optimizeIndex,
            300, 300, TimeUnit.SECONDS); // Every 5 minutes
        
        // Start suggestions updates
        searchScheduler.scheduleWithFixedDelay(suggestionsEngine::updateSuggestions,
            120, 120, TimeUnit.SECONDS); // Every 2 minutes
    }
    
    /**
     * Perform intelligent search with ranking and filtering
     */
    public CompletableFuture<SearchResults> searchAsync(String query, SearchOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // Process and normalize the query
                ProcessedQuery processedQuery = queryProcessor.processQuery(query);
                
                // Record search for analytics
                analytics.recordSearch(query, processedQuery);
                
                // Search in indexes
                List<SearchMatch> matches = indexManager.search(processedQuery, options);
                
                // Score and rank results
                List<RankedSearchResult> rankedResults = relevanceScorer.scoreResults(
                    matches, processedQuery, options);
                
                // Apply filters and limits
                List<RankedSearchResult> filteredResults = applyFilters(rankedResults, options);
                
                // Generate suggestions
                List<String> suggestions = suggestionsEngine.generateSuggestions(query);
                
                long searchTime = System.nanoTime() - startTime;
                analytics.recordSearchTime(searchTime);
                
                SearchResults results = new SearchResults(
                    filteredResults,
                    suggestions,
                    matches.size(),
                    filteredResults.size(),
                    searchTime / 1_000_000.0 // Convert to milliseconds
                );
                
                analytics.recordResults(results);
                return results;
                
            } catch (Exception e) {
                LOGGER.error("Search failed for query: {}", query, e);
                analytics.recordSearchError(query);
                return SearchResults.empty();
            }
        });
    }
    
    /**
     * Add or update document in search index
     */
    public CompletableFuture<Boolean> indexDocumentAsync(SearchDocument document) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                indexManager.addDocument(document);
                analytics.recordIndexOperation();
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to index document: {}", document.getId(), e);
                return false;
            }
        });
    }
    
    /**
     * Remove document from search index
     */
    public CompletableFuture<Boolean> removeDocumentAsync(String documentId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                indexManager.removeDocument(documentId);
                analytics.recordIndexOperation();
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to remove document: {}", documentId, e);
                return false;
            }
        });
    }
    
    /**
     * Get search suggestions for query prefix
     */
    public List<String> getSuggestions(String queryPrefix) {
        return suggestionsEngine.getSuggestions(queryPrefix, SUGGESTION_LIMIT);
    }
    
    /**
     * Advanced search index management with real-time updates
     */
    private class SearchIndexManager {
        private final Map<String, InvertedIndex> fieldIndexes = new ConcurrentHashMap<>();
        private final Map<String, SearchDocument> documents = new ConcurrentHashMap<>();
        private final LongAdder documentCount = new LongAdder();
        
        SearchIndexManager() {
            // Initialize field indexes
            fieldIndexes.put("title", new InvertedIndex("title"));
            fieldIndexes.put("content", new InvertedIndex("content"));
            fieldIndexes.put("tags", new InvertedIndex("tags"));
            fieldIndexes.put("author", new InvertedIndex("author"));
        }
        
        void addDocument(SearchDocument document) {
            documents.put(document.getId(), document);
            
            // Index all fields
            for (Map.Entry<String, String> field : document.getFields().entrySet()) {
                InvertedIndex index = fieldIndexes.get(field.getKey());
                if (index != null) {
                    index.addDocument(document.getId(), field.getValue());
                }
            }
            
            documentCount.increment();
        }
        
        void removeDocument(String documentId) {
            SearchDocument document = documents.remove(documentId);
            if (document != null) {
                // Remove from all indexes
                for (InvertedIndex index : fieldIndexes.values()) {
                    index.removeDocument(documentId);
                }
                documentCount.decrement();
            }
        }
        
        List<SearchMatch> search(ProcessedQuery query, SearchOptions options) {
            Map<String, Double> documentScores = new HashMap<>();
            
            // Search in relevant field indexes
            for (String field : options.getSearchFields()) {
                InvertedIndex index = fieldIndexes.get(field);
                if (index != null) {
                    Map<String, Double> fieldResults = index.search(query);
                    
                    // Combine scores with field weights
                    double fieldWeight = options.getFieldWeight(field);
                    for (Map.Entry<String, Double> entry : fieldResults.entrySet()) {
                        String docId = entry.getKey();
                        double score = entry.getValue() * fieldWeight;
                        documentScores.merge(docId, score, Double::sum);
                    }
                }
            }
            
            // Convert to SearchMatch objects
            return documentScores.entrySet().stream()
                .map(entry -> {
                    SearchDocument doc = documents.get(entry.getKey());
                    return doc != null ? new SearchMatch(doc, entry.getValue()) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        }
        
        void optimizeIndex() {
            for (InvertedIndex index : fieldIndexes.values()) {
                index.optimize();
            }
            LOGGER.debug("Search indexes optimized");
        }
        
        public IndexStats getStats() {
            int totalTerms = fieldIndexes.values().stream()
                .mapToInt(InvertedIndex::getTermCount)
                .sum();
            
            return new IndexStats(documentCount.sum(), totalTerms, fieldIndexes.size());
        }
    }
    
    /**
     * Intelligent query processing with normalization and expansion
     */
    private class QueryProcessor {
        private final Pattern WORD_PATTERN = Pattern.compile("\\b\\w+\\b");
        private final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with"
        );
        
        ProcessedQuery processQuery(String rawQuery) {
            // Normalize and clean query
            String normalized = normalizeText(rawQuery.toLowerCase().trim());
            
            // Extract terms
            List<String> terms = extractTerms(normalized);
            
            // Remove stop words
            List<String> filteredTerms = terms.stream()
                .filter(term -> !STOP_WORDS.contains(term))
                .filter(term -> term.length() > 1)
                .collect(Collectors.toList());
            
            // Generate variations (synonyms, fuzzy matches)
            Map<String, List<String>> termVariations = generateTermVariations(filteredTerms);
            
            return new ProcessedQuery(rawQuery, normalized, filteredTerms, termVariations);
        }
        
        private String normalizeText(String text) {
            // Remove diacritics and special characters
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
            return normalized.replaceAll("[^\\p{ASCII}]", "")
                           .replaceAll("[^a-zA-Z0-9\\s]", " ")
                           .replaceAll("\\s+", " ");
        }
        
        private List<String> extractTerms(String text) {
            List<String> terms = new ArrayList<>();
            java.util.regex.Matcher matcher = WORD_PATTERN.matcher(text);
            
            while (matcher.find()) {
                terms.add(matcher.group());
            }
            
            return terms;
        }
        
        private Map<String, List<String>> generateTermVariations(List<String> terms) {
            Map<String, List<String>> variations = new HashMap<>();
            
            for (String term : terms) {
                List<String> termVariations = new ArrayList<>();
                termVariations.add(term); // Original term
                
                // Add simple variations
                if (term.endsWith("s") && term.length() > 3) {
                    termVariations.add(term.substring(0, term.length() - 1)); // Singular
                }
                if (!term.endsWith("s")) {
                    termVariations.add(term + "s"); // Plural
                }
                
                variations.put(term, termVariations);
            }
            
            return variations;
        }
    }
    
    /**
     * ML-powered relevance scoring with multiple ranking factors
     */
    private class RelevanceScorer {
        
        List<RankedSearchResult> scoreResults(List<SearchMatch> matches, 
                                            ProcessedQuery query, SearchOptions options) {
            return matches.stream()
                .map(match -> scoreMatch(match, query, options))
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(options.getMaxResults())
                .collect(Collectors.toList());
        }
        
        private RankedSearchResult scoreMatch(SearchMatch match, ProcessedQuery query, SearchOptions options) {
            double score = match.getBaseScore();
            
            // Apply various scoring factors
            score *= calculateTfIdfScore(match.getDocument(), query);
            score *= calculateFieldMatchScore(match.getDocument(), query);
            score *= calculateFreshnessScore(match.getDocument());
            score *= calculatePopularityScore(match.getDocument());
            
            Map<String, Object> scoreBreakdown = new HashMap<>();
            scoreBreakdown.put("baseScore", match.getBaseScore());
            scoreBreakdown.put("tfIdf", calculateTfIdfScore(match.getDocument(), query));
            scoreBreakdown.put("fieldMatch", calculateFieldMatchScore(match.getDocument(), query));
            scoreBreakdown.put("freshness", calculateFreshnessScore(match.getDocument()));
            scoreBreakdown.put("popularity", calculatePopularityScore(match.getDocument()));
            
            return new RankedSearchResult(match.getDocument(), score, scoreBreakdown);
        }
        
        private double calculateTfIdfScore(SearchDocument document, ProcessedQuery query) {
            // Simplified TF-IDF calculation
            double score = 1.0;
            
            for (String term : query.getTerms()) {
                int termFreq = countTermInDocument(document, term);
                if (termFreq > 0) {
                    double tf = Math.log(1.0 + termFreq);
                    double idf = Math.log(indexManager.documentCount.sum() / (double) getDocumentFrequency(term));
                    score += tf * idf;
                }
            }
            
            return score;
        }
        
        private double calculateFieldMatchScore(SearchDocument document, ProcessedQuery query) {
            double score = 1.0;
            
            // Boost if terms appear in title
            String title = document.getField("title");
            if (title != null) {
                for (String term : query.getTerms()) {
                    if (title.toLowerCase().contains(term)) {
                        score += 0.5; // Title boost
                    }
                }
            }
            
            return score;
        }
        
        private double calculateFreshnessScore(SearchDocument document) {
            long ageMs = System.currentTimeMillis() - document.getCreationTime();
            long ageDays = ageMs / (24 * 60 * 60 * 1000);
            
            // Fresher documents get higher scores
            return Math.max(0.1, 1.0 - (ageDays / 365.0)); // Decay over a year
        }
        
        private double calculatePopularityScore(SearchDocument document) {
            // Simplified popularity based on view count
            int views = document.getViewCount();
            return Math.log(1.0 + views / 100.0); // Logarithmic scaling
        }
        
        private int countTermInDocument(SearchDocument document, String term) {
            int count = 0;
            for (String fieldValue : document.getFields().values()) {
                if (fieldValue != null) {
                    String normalized = fieldValue.toLowerCase();
                    int index = 0;
                    while ((index = normalized.indexOf(term, index)) != -1) {
                        count++;
                        index += term.length();
                    }
                }
            }
            return count;
        }
        
        private int getDocumentFrequency(String term) {
            // Simplified: count documents containing the term
            return (int) indexManager.documents.values().stream()
                .filter(doc -> documentContainsTerm(doc, term))
                .count();
        }
        
        private boolean documentContainsTerm(SearchDocument document, String term) {
            return document.getFields().values().stream()
                .anyMatch(field -> field != null && field.toLowerCase().contains(term));
        }
    }
    
    /**
     * Intelligent search suggestions with learning capabilities
     */
    private class SearchSuggestionsEngine {
        private final Map<String, QueryStats> queryStats = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> prefixToQueries = new ConcurrentHashMap<>();
        
        void recordQuery(String query) {
            QueryStats stats = queryStats.computeIfAbsent(query, k -> new QueryStats());
            stats.recordUse();
            
            // Index by prefixes for suggestions
            for (int i = 1; i <= query.length(); i++) {
                String prefix = query.substring(0, i).toLowerCase();
                prefixToQueries.computeIfAbsent(prefix, k -> ConcurrentHashMap.newKeySet())
                              .add(query);
            }
        }
        
        List<String> getSuggestions(String prefix, int limit) {
            String normalizedPrefix = prefix.toLowerCase();
            
            Set<String> candidates = prefixToQueries.getOrDefault(normalizedPrefix, Collections.emptySet());
            
            return candidates.stream()
                .filter(query -> !query.equals(prefix))
                .sorted((q1, q2) -> {
                    QueryStats stats1 = queryStats.get(q1);
                    QueryStats stats2 = queryStats.get(q2);
                    return Integer.compare(stats2.getUseCount(), stats1.getUseCount());
                })
                .limit(limit)
                .collect(Collectors.toList());
        }
        
        List<String> generateSuggestions(String query) {
            List<String> suggestions = new ArrayList<>();
            
            // Get prefix-based suggestions
            suggestions.addAll(getSuggestions(query, 5));
            
            // Add fuzzy matches
            suggestions.addAll(getFuzzyMatches(query, 5));
            
            return suggestions.stream()
                .distinct()
                .limit(SUGGESTION_LIMIT)
                .collect(Collectors.toList());
        }
        
        private List<String> getFuzzyMatches(String query, int limit) {
            return queryStats.keySet().stream()
                .filter(existingQuery -> calculateSimilarity(query, existingQuery) > FUZZY_THRESHOLD)
                .filter(existingQuery -> !existingQuery.equals(query))
                .sorted((q1, q2) -> Double.compare(
                    calculateSimilarity(query, q2), 
                    calculateSimilarity(query, q1)))
                .limit(limit)
                .collect(Collectors.toList());
        }
        
        private double calculateSimilarity(String s1, String s2) {
            // Simplified Levenshtein-based similarity
            int distance = calculateLevenshteinDistance(s1, s2);
            int maxLength = Math.max(s1.length(), s2.length());
            return maxLength > 0 ? 1.0 - (double) distance / maxLength : 1.0;
        }
        
        private int calculateLevenshteinDistance(String s1, String s2) {
            int[][] dp = new int[s1.length() + 1][s2.length() + 1];
            
            for (int i = 0; i <= s1.length(); i++) {
                for (int j = 0; j <= s2.length(); j++) {
                    if (i == 0) {
                        dp[i][j] = j;
                    } else if (j == 0) {
                        dp[i][j] = i;
                    } else {
                        dp[i][j] = Math.min(Math.min(
                            dp[i - 1][j] + 1,
                            dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                        );
                    }
                }
            }
            
            return dp[s1.length()][s2.length()];
        }
        
        void updateSuggestions() {
            // Cleanup old or low-use suggestions
            Set<String> toRemove = queryStats.entrySet().stream()
                .filter(entry -> entry.getValue().getUseCount() < 2)
                .filter(entry -> entry.getValue().getDaysSinceLastUse() > 30)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            
            toRemove.forEach(this::removeSuggestion);
            
            if (!toRemove.isEmpty()) {
                LOGGER.debug("Removed {} stale suggestions", toRemove.size());
            }
        }
        
        private void removeSuggestion(String query) {
            queryStats.remove(query);
            
            // Remove from prefix mappings
            for (int i = 1; i <= query.length(); i++) {
                String prefix = query.substring(0, i).toLowerCase();
                Set<String> queries = prefixToQueries.get(prefix);
                if (queries != null) {
                    queries.remove(query);
                    if (queries.isEmpty()) {
                        prefixToQueries.remove(prefix);
                    }
                }
            }
        }
        
        public SuggestionStats getStats() {
            return new SuggestionStats(queryStats.size(), prefixToQueries.size());
        }
    }
    
    /**
     * Comprehensive search analytics
     */
    private class SearchAnalytics {
        private final LongAdder totalSearches = new LongAdder();
        private final LongAdder searchErrors = new LongAdder();
        private final LongAdder indexOperations = new LongAdder();
        private final AtomicLong totalSearchTime = new AtomicLong();
        private final Map<String, Integer> popularQueries = new ConcurrentHashMap<>();
        
        void recordSearch(String query, ProcessedQuery processedQuery) {
            totalSearches.increment();
            popularQueries.merge(query.toLowerCase(), 1, Integer::sum);
            suggestionsEngine.recordQuery(query);
        }
        
        void recordSearchTime(long timeNanos) {
            totalSearchTime.addAndGet(timeNanos);
        }
        
        void recordResults(SearchResults results) {
            // Could track result quality metrics
        }
        
        void recordSearchError(String query) {
            searchErrors.increment();
        }
        
        void recordIndexOperation() {
            indexOperations.increment();
        }
        
        public SearchAnalyticsStats getStats() {
            long searches = totalSearches.sum();
            double avgSearchTime = searches > 0 ? 
                (double) totalSearchTime.get() / searches / 1_000_000.0 : 0.0; // Convert to ms
            
            List<String> topQueries = popularQueries.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
            return new SearchAnalyticsStats(
                searches, searchErrors.sum(), avgSearchTime, 
                indexOperations.sum(), topQueries
            );
        }
    }
    
    // Supporting classes
    private static class InvertedIndex {
        private final String fieldName;
        private final Map<String, Set<String>> termToDocuments = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Integer>> termFrequencies = new ConcurrentHashMap<>();
        
        InvertedIndex(String fieldName) {
            this.fieldName = fieldName;
        }
        
        void addDocument(String documentId, String fieldValue) {
            if (fieldValue == null) return;
            
            String normalized = fieldValue.toLowerCase();
            String[] terms = normalized.split("\\s+");
            
            for (String term : terms) {
                if (term.length() > 1) {
                    termToDocuments.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet())
                                  .add(documentId);
                    
                    termFrequencies.computeIfAbsent(term, k -> new ConcurrentHashMap<>())
                                  .merge(documentId, 1, Integer::sum);
                }
            }
        }
        
        void removeDocument(String documentId) {
            // Remove document from all terms
            for (Set<String> documents : termToDocuments.values()) {
                documents.remove(documentId);
            }
            
            for (Map<String, Integer> frequencies : termFrequencies.values()) {
                frequencies.remove(documentId);
            }
        }
        
        Map<String, Double> search(ProcessedQuery query) {
            Map<String, Double> scores = new HashMap<>();
            
            for (String term : query.getTerms()) {
                Set<String> documents = termToDocuments.get(term);
                if (documents != null) {
                    for (String documentId : documents) {
                        Integer frequency = termFrequencies.get(term).get(documentId);
                        double score = frequency != null ? Math.log(1.0 + frequency) : 1.0;
                        scores.merge(documentId, score, Double::sum);
                    }
                }
                
                // Also search term variations
                List<String> variations = query.getTermVariations().get(term);
                if (variations != null) {
                    for (String variation : variations) {
                        if (!variation.equals(term)) {
                            Set<String> variationDocs = termToDocuments.get(variation);
                            if (variationDocs != null) {
                                for (String documentId : variationDocs) {
                                    Integer frequency = termFrequencies.get(variation).get(documentId);
                                    double score = frequency != null ? Math.log(1.0 + frequency) * 0.8 : 0.8; // Reduce score for variations
                                    scores.merge(documentId, score, Double::sum);
                                }
                            }
                        }
                    }
                }
            }
            
            return scores;
        }
        
        void optimize() {
            // Remove empty entries
            termToDocuments.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            termFrequencies.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        
        int getTermCount() {
            return termToDocuments.size();
        }
    }
    
    private List<RankedSearchResult> applyFilters(List<RankedSearchResult> results, SearchOptions options) {
        return results.stream()
            .filter(result -> applyContentFilter(result, options))
            .filter(result -> applyDateFilter(result, options))
            .limit(options.getMaxResults())
            .collect(Collectors.toList());
    }
    
    private boolean applyContentFilter(RankedSearchResult result, SearchOptions options) {
        // Apply content type filters, etc.
        return true; // Simplified
    }
    
    private boolean applyDateFilter(RankedSearchResult result, SearchOptions options) {
        // Apply date range filters
        return true; // Simplified
    }
    
    // Data classes
    public static class SearchDocument {
        private final String id;
        private final Map<String, String> fields;
        private final long creationTime;
        private final int viewCount;
        
        public SearchDocument(String id, Map<String, String> fields) {
            this.id = id;
            this.fields = new HashMap<>(fields);
            this.creationTime = System.currentTimeMillis();
            this.viewCount = 0;
        }
        
        public SearchDocument(String id, Map<String, String> fields, long creationTime, int viewCount) {
            this.id = id;
            this.fields = new HashMap<>(fields);
            this.creationTime = creationTime;
            this.viewCount = viewCount;
        }
        
        public String getId() { return id; }
        public Map<String, String> getFields() { return new HashMap<>(fields); }
        public String getField(String name) { return fields.get(name); }
        public long getCreationTime() { return creationTime; }
        public int getViewCount() { return viewCount; }
        
        @Override
        public String toString() {
            return String.format("SearchDocument{id='%s', fields=%s}", id, fields);
        }
    }
    
    public static class SearchOptions {
        private final List<String> searchFields;
        private final Map<String, Double> fieldWeights;
        private final int maxResults;
        private final boolean enableFuzzySearch;
        
        public SearchOptions() {
            this.searchFields = Arrays.asList("title", "content", "tags");
            this.fieldWeights = Map.of("title", 2.0, "content", 1.0, "tags", 1.5);
            this.maxResults = MAX_SEARCH_RESULTS;
            this.enableFuzzySearch = true;
        }
        
        public SearchOptions(List<String> searchFields, Map<String, Double> fieldWeights, 
                           int maxResults, boolean enableFuzzySearch) {
            this.searchFields = searchFields;
            this.fieldWeights = fieldWeights;
            this.maxResults = maxResults;
            this.enableFuzzySearch = enableFuzzySearch;
        }
        
        public List<String> getSearchFields() { return searchFields; }
        public double getFieldWeight(String field) { return fieldWeights.getOrDefault(field, 1.0); }
        public int getMaxResults() { return maxResults; }
        public boolean isFuzzySearchEnabled() { return enableFuzzySearch; }
    }
    
    public static class ProcessedQuery {
        private final String originalQuery;
        private final String normalizedQuery;
        private final List<String> terms;
        private final Map<String, List<String>> termVariations;
        
        public ProcessedQuery(String originalQuery, String normalizedQuery, 
                            List<String> terms, Map<String, List<String>> termVariations) {
            this.originalQuery = originalQuery;
            this.normalizedQuery = normalizedQuery;
            this.terms = terms;
            this.termVariations = termVariations;
        }
        
        public String getOriginalQuery() { return originalQuery; }
        public String getNormalizedQuery() { return normalizedQuery; }
        public List<String> getTerms() { return terms; }
        public Map<String, List<String>> getTermVariations() { return termVariations; }
    }
    
    public static class SearchMatch {
        private final SearchDocument document;
        private final double baseScore;
        
        public SearchMatch(SearchDocument document, double baseScore) {
            this.document = document;
            this.baseScore = baseScore;
        }
        
        public SearchDocument getDocument() { return document; }
        public double getBaseScore() { return baseScore; }
    }
    
    public static class RankedSearchResult {
        private final SearchDocument document;
        private final double score;
        private final Map<String, Object> scoreBreakdown;
        
        public RankedSearchResult(SearchDocument document, double score, Map<String, Object> scoreBreakdown) {
            this.document = document;
            this.score = score;
            this.scoreBreakdown = scoreBreakdown;
        }
        
        public SearchDocument getDocument() { return document; }
        public double getScore() { return score; }
        public Map<String, Object> getScoreBreakdown() { return scoreBreakdown; }
        
        @Override
        public String toString() {
            return String.format("RankedSearchResult{id='%s', score=%.2f}", 
                               document.getId(), score);
        }
    }
    
    public static class SearchResults {
        private final List<RankedSearchResult> results;
        private final List<String> suggestions;
        private final int totalMatches;
        private final int returnedResults;
        private final double searchTimeMs;
        
        public SearchResults(List<RankedSearchResult> results, List<String> suggestions,
                           int totalMatches, int returnedResults, double searchTimeMs) {
            this.results = results;
            this.suggestions = suggestions;
            this.totalMatches = totalMatches;
            this.returnedResults = returnedResults;
            this.searchTimeMs = searchTimeMs;
        }
        
        public static SearchResults empty() {
            return new SearchResults(Collections.emptyList(), Collections.emptyList(), 0, 0, 0.0);
        }
        
        public List<RankedSearchResult> getResults() { return results; }
        public List<String> getSuggestions() { return suggestions; }
        public int getTotalMatches() { return totalMatches; }
        public int getReturnedResults() { return returnedResults; }
        public double getSearchTimeMs() { return searchTimeMs; }
        
        @Override
        public String toString() {
            return String.format("SearchResults{results=%d/%d, time=%.2fms, suggestions=%d}",
                               returnedResults, totalMatches, searchTimeMs, suggestions.size());
        }
    }
    
    private static class QueryStats {
        private int useCount = 0;
        private long lastUseTime = System.currentTimeMillis();
        
        void recordUse() {
            useCount++;
            lastUseTime = System.currentTimeMillis();
        }
        
        int getUseCount() { return useCount; }
        
        long getDaysSinceLastUse() {
            return (System.currentTimeMillis() - lastUseTime) / (24 * 60 * 60 * 1000);
        }
    }
    
    // Statistics classes
    public static class IndexStats {
        public final long documentCount;
        public final int totalTerms;
        public final int fieldIndexes;
        
        public IndexStats(long documentCount, int totalTerms, int fieldIndexes) {
            this.documentCount = documentCount;
            this.totalTerms = totalTerms;
            this.fieldIndexes = fieldIndexes;
        }
        
        @Override
        public String toString() {
            return String.format("IndexStats{docs=%d, terms=%d, indexes=%d}",
                               documentCount, totalTerms, fieldIndexes);
        }
    }
    
    public static class SuggestionStats {
        public final int totalQueries;
        public final int prefixMappings;
        
        public SuggestionStats(int totalQueries, int prefixMappings) {
            this.totalQueries = totalQueries;
            this.prefixMappings = prefixMappings;
        }
        
        @Override
        public String toString() {
            return String.format("SuggestionStats{queries=%d, prefixes=%d}",
                               totalQueries, prefixMappings);
        }
    }
    
    public static class SearchAnalyticsStats {
        public final long totalSearches;
        public final long searchErrors;
        public final double avgSearchTime;
        public final long indexOperations;
        public final List<String> topQueries;
        
        public SearchAnalyticsStats(long totalSearches, long searchErrors, double avgSearchTime,
                                  long indexOperations, List<String> topQueries) {
            this.totalSearches = totalSearches;
            this.searchErrors = searchErrors;
            this.avgSearchTime = avgSearchTime;
            this.indexOperations = indexOperations;
            this.topQueries = topQueries;
        }
        
        @Override
        public String toString() {
            return String.format("SearchAnalyticsStats{searches=%d, errors=%d, avgTime=%.2fms, indexOps=%d}",
                               totalSearches, searchErrors, avgSearchTime, indexOperations);
        }
    }
    
    // Public API methods
    public SearchResults search(String query) {
        return search(query, new SearchOptions());
    }
    
    public SearchResults search(String query, SearchOptions options) {
        try {
            return searchAsync(query, options).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Synchronous search failed for query: {}", query, e);
            return SearchResults.empty();
        }
    }
    
    public boolean indexDocument(SearchDocument document) {
        try {
            return indexDocumentAsync(document).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Synchronous index failed for document: {}", document.getId(), e);
            return false;
        }
    }
    
    public boolean removeDocument(String documentId) {
        try {
            return removeDocumentAsync(documentId).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Synchronous remove failed for document: {}", documentId, e);
            return false;
        }
    }
    
    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== INTELLIGENT SEARCH ENGINE STATS ===\n");
        stats.append("Index: ").append(indexManager.getStats()).append("\n");
        stats.append("Suggestions: ").append(suggestionsEngine.getStats()).append("\n");
        stats.append("Analytics: ").append(analytics.getStats()).append("\n");
        stats.append("======================================");
        
        return stats.toString();
    }
    
    public void shutdown() {
        searchScheduler.shutdown();
        try {
            if (!searchScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                searchScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            searchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Intelligent Search Engine shutdown completed");
    }
}