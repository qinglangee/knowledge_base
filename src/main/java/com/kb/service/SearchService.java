package com.kb.service;

import com.kb.common.BusinessException;
import com.kb.dto.Dtos.SearchItem;
import com.kb.dto.Dtos.SearchResponse;
import com.kb.entity.DbLibrary;
import com.kb.repository.DbLibraryRepository;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索服务：合并多个库的索引（MultiReader），命中高亮摘要，分页。
 */
@Service
public class SearchService {

    private static final int MAX_HITS = 20000;

    private final DbLibraryRepository libRepo;
    private final IndexService indexService;
    private final SettingService settingService;

    public SearchService(DbLibraryRepository libRepo,
                         IndexService indexService,
                         SettingService settingService) {
        this.libRepo = libRepo;
        this.indexService = indexService;
        this.settingService = settingService;
    }

    /**
     * @param ext  扩展名过滤，逗号/空格分隔，如 "pdf, docx"；空表示不过滤
     * @param from 修改时间下限（含），格式 yyyy-MM-dd；空表示不限
     * @param to   修改时间上限（含当天），格式 yyyy-MM-dd；空表示不限
     */
    public SearchResponse search(String q, Long libId, Integer page, Integer size,
                                 String ext, String from, String to) {
        if (q == null || q.isBlank()) {
            throw new BusinessException("查询词不能为空");
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null ? settingService.pageSize() : size;
        s = Math.min(Math.max(s, 1), 100);
        List<String> exts = parseExts(ext);
        Long fromMs = toMillis(from, true);
        Long toMs = toMillis(to, false);
        Analyzer analyzer = indexService.analyzer();
        long t0 = System.currentTimeMillis();

        List<DbLibrary> libs;
        if (libId == null) {
            libs = libRepo.findAll();
        } else {
            DbLibrary lib = libRepo.findById(libId)
                    .orElseThrow(() -> new BusinessException(404, "索引库不存在"));
            libs = List.of(lib);
        }

        List<IndexReader> readers = new ArrayList<>();
        try {
            for (DbLibrary lib : libs) {
                Path dir = indexService.resolveLibIndexDir(lib);
                if (!Files.exists(dir)) {
                    continue;
                }
                FSDirectory fsDir = FSDirectory.open(dir);
                if (!DirectoryReader.indexExists(fsDir)) {
                    continue;
                }
                readers.add(DirectoryReader.open(fsDir));
            }
            if (readers.isEmpty()) {
                return new SearchResponse(0, p, s, System.currentTimeMillis() - t0, List.of());
            }

            MultiReader multi = new MultiReader(readers.toArray(new IndexReader[0]), false);
            IndexSearcher searcher = new IndexSearcher(multi);
            MultiFieldQueryParser parser =
                    new MultiFieldQueryParser(new String[]{"name", "content"}, analyzer);
            Query textQuery = parser.parse(q.trim());

            // 拼装过滤条件（filter 上下文，不影响相关性打分）
            Query query = textQuery;
            if (!exts.isEmpty() || fromMs != null || toMs != null) {
                BooleanQuery.Builder top = new BooleanQuery.Builder();
                top.add(textQuery, BooleanClause.Occur.MUST);
                if (!exts.isEmpty()) {
                    BooleanQuery.Builder eb = new BooleanQuery.Builder();
                    for (String e : exts) {
                        eb.add(new TermQuery(new Term("ext", e)), BooleanClause.Occur.SHOULD);
                    }
                    top.add(eb.build(), BooleanClause.Occur.FILTER);
                }
                if (fromMs != null || toMs != null) {
                    long lo = fromMs == null ? 0L : fromMs;
                    long hi = toMs == null ? Long.MAX_VALUE : toMs;
                    top.add(LongPoint.newRangeQuery("mtimePoint", lo, hi), BooleanClause.Occur.FILTER);
                }
                query = top.build();
            }

            int cap = Math.min((p + 1) * s, MAX_HITS);
            TopDocs topDocs = searcher.search(query, cap);

            Highlighter highlighter = new Highlighter(
                    new SimpleHTMLFormatter("<em>", "</em>"), new QueryScorer(textQuery, "content"));
            highlighter.setTextFragmenter(new SimpleFragmenter(160));

            Map<Long, String> libNames = libs.stream()
                    .collect(Collectors.toMap(DbLibrary::getId, DbLibrary::getName));

            List<SearchItem> items = new ArrayList<>();
            int fromIdx = p * s;
            int toIdx = Math.min(topDocs.scoreDocs.length, fromIdx + s);
            var storedFields = searcher.storedFields();
            for (int i = fromIdx; i < toIdx; i++) {
                ScoreDoc hit = topDocs.scoreDocs[i];
                Document doc = storedFields.document(hit.doc);
                String content = doc.get("content");
                if (content == null) {
                    content = "";
                }
                Long lid = parseLong(doc.get("libId"));
                items.add(new SearchItem(
                        doc.get("name"),
                        doc.get("path"),
                        doc.get("ext"),
                        longOf(doc, "size"),
                        longOf(doc, "mtime"),
                        hit.score,
                        snippet(highlighter, content, analyzer),
                        lid,
                        lid == null ? null : libNames.get(lid)));
            }
            return new SearchResponse(topDocs.totalHits.value, p, s, System.currentTimeMillis() - t0, items);
        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            throw new BusinessException("查询语法错误: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(500, "搜索失败: " + e.getMessage());
        } finally {
            for (IndexReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String snippet(Highlighter highlighter, String content, Analyzer analyzer) {
        if (content.isBlank()) {
            return "";
        }
        try (TokenStream ts = analyzer.tokenStream("content", content)) {
            String[] fragments = highlighter.getBestFragments(ts, content, 2);
            if (fragments.length > 0) {
                return String.join(" … ", fragments).replaceAll("\\s+", " ").trim();
            }
        } catch (IOException | InvalidTokenOffsetsException ignored) {
        }
        String plain = content.replaceAll("\\s+", " ").trim();
        return plain.length() > 200 ? plain.substring(0, 200) + "…" : plain;
    }

    /**
     * 解析扩展名过滤参数："pdf, docx" / "PDF .md" -> ["pdf", "docx", "md"]。
     */
    private static List<String> parseExts(String ext) {
        if (ext == null || ext.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ext.split("[,，;；\\s]+"))
                .map(String::trim)
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 解析 yyyy-MM-dd 日期：startOfDay=true 取当天 0 点，否则取当天 23:59:59.999。
     */
    private static Long toMillis(String date, boolean startOfDay) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(date.trim());
            return startOfDay
                    ? d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : d.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        } catch (DateTimeParseException e) {
            throw new BusinessException("时间格式无效: " + date + "（应为 yyyy-MM-dd）");
        }
    }

    private static long longOf(Document doc, String field) {
        IndexableField f = doc.getField(field);
        return f == null || f.numericValue() == null ? 0L : f.numericValue().longValue();
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
