package org.elasticsearch.index.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.elasticsearch.analysis.PinyinConfig;
import org.nlpcn.commons.lang.pinyin.Pinyin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;


public class PinyinTokenizer extends Tokenizer {


    private static final int DEFAULT_BUFFER_SIZE = 256;
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private boolean done = false;
    private boolean processedCandidate = false;
    private boolean processedSortCandidate = false;
    private boolean processedFirstLetter = false;
    private boolean processedFullPinyinLetter = false;
    private boolean processedOriginal = false;
    protected int position = 0;
    protected int lastPosition = 0;
    private OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private PositionIncrementAttribute positionAttr = addAttribute(PositionIncrementAttribute.class);
    private PinyinConfig config;
    ArrayList<TermItem> candidate;
    protected int candidateOffset = 0; //indicate candidates process offset
    private HashSet<String> termsFilter;
    StringBuilder firstLetters;
    StringBuilder fullPinyinLetters;

    private int lastIncrementPosition = 0;

    String source;

    public PinyinTokenizer(PinyinConfig config) {
        this(DEFAULT_BUFFER_SIZE);
        this.config = config;

        //validate config
        if (!(config.keepFirstLetter || config.keepSeparateFirstLetter || config.keepFullPinyin || config.keepJoinedFullPinyin)) {
            throw new ConfigErrorException("pinyin config error, can't disable separate_first_letter, first_letter and full_pinyin at the same time.");
        }
        candidate = new ArrayList<>();
        termsFilter = new HashSet<>();
        firstLetters = new StringBuilder();
        fullPinyinLetters = new StringBuilder();
    }

    public PinyinTokenizer(int bufferSize) {
        super();
        termAtt.resizeBuffer(bufferSize);
    }

    void addCandidate(TermItem item) {

        String term = item.term;
        if (config.lowercase) {
            term = term.toLowerCase();
        }

        if (config.trimWhitespace) {
            term = term.trim();
        }
        item.term = term;

        if (term.length() == 0) {
            return;
        }

        if (config.removeDuplicateTerm) {
            if (termsFilter.contains(term)) {
                return;
            }
            termsFilter.add(term);
        }
        candidate.add(item);
    }


    void setTerm(String term, int startOffset, int endOffset, int position) {
        if (config.lowercase) {
            term = term.toLowerCase();
        }

        if (config.trimWhitespace) {
            term = term.trim();
        }
        termAtt.setEmpty();
        termAtt.append(term);
        if (startOffset < 0) {
            startOffset = 0;
        }
        if (endOffset < startOffset) {
            endOffset = startOffset + term.length();
        }
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));

        int offset = position - lastIncrementPosition;
//        System.out.println("term:"+term+",position:"+position+",last:"+lastIncrementPosition+",offset:"+offset);
        if (offset < 0) {
            offset = 0;
        }
        positionAttr.setPositionIncrement(offset);

        lastIncrementPosition = position;
    }

    @Override
    public final boolean incrementToken() throws IOException {

        clearAttributes();

        if (!done) {

            //combine text together to get right pinyin
            if (!processedCandidate) {
                processedCandidate = true;
                int upto = 0;
                char[] buffer = termAtt.buffer();
                while (true) {
                    final int length = input.read(buffer, upto, buffer.length - upto);
                    if (length == -1) break;
                    upto += length;
                    if (upto == buffer.length)
                        buffer = termAtt.resizeBuffer(1 + buffer.length);
                }
                termAtt.setLength(upto);
                source = termAtt.toString();

                List<String> pinyinList = Pinyin.pinyin(source);

                StringBuilder buff = new StringBuilder();
                int buffStartPosition = 0;
                int buffSize = 0;

                for (int i = 0; i < source.length(); i++) {
                    char c = source.charAt(i);
                    position = i;
                    //keep original alphabet
                    if (c < 128) {
                        if (buff.length() <= 0) {
                            buffStartPosition = i;
                        }
                        if ((c > 96 && c < 123) || (c > 64 && c < 91) || (c > 47 && c < 58)) {
                            if (config.keepNoneChinese) {
                                if (config.keepNoneChinese) {
                                    if (config.keepNoneChineseTogether) {
                                        buff.append(c);
                                        buffSize++;
                                    } else {
                                        addCandidate(new TermItem(String.valueOf(c), i, i + 1, buffStartPosition));
                                    }
                                }
                            }
                            if (config.keepNoneChineseInFirstLetter) {
                                firstLetters.append(c);
                            }
                            if (config.keepNoneChineseInJoinedFullPinyin) {
                                fullPinyinLetters.append(c);
                            }
                        }
                    } else {
                        //clean previous temp
                        if (buff.length() > 0) {
                            buffSize = parseBuff(buff, buffSize, buffStartPosition);
                        }

                        String pinyin = pinyinList.get(i);
                        if (pinyin != null && pinyin.length() > 0) {

                            firstLetters.append(pinyin.charAt(0));
                            if (config.keepSeparateFirstLetter & pinyin.length() > 1) {
                                addCandidate(new TermItem(String.valueOf(pinyin.charAt(0)), i, i + 1, position));
                            }
                            if (config.keepFullPinyin) {
                                addCandidate(new TermItem(pinyin, i, i + 1, position));
                            }
                            if (config.keepJoinedFullPinyin) {
                                fullPinyinLetters.append(pinyin);
                            }
                        }
                    }

                    lastPosition = i;

                }

                //clean previous temp
                if (buff.length() > 0) {
                    buffSize = parseBuff(buff, buffSize, buffStartPosition);
                }
            }

            if (config.keepOriginal && !processedOriginal) {
                processedOriginal = true;
                addCandidate(new TermItem(source, 0, source.length(), 0));
            }

            if (config.keepJoinedFullPinyin && !processedFullPinyinLetter && fullPinyinLetters.length() > 0) {
                processedFullPinyinLetter = true;
                addCandidate(new TermItem(fullPinyinLetters.toString(), 0, fullPinyinLetters.length(), 0));
                fullPinyinLetters.setLength(0);
            }


            if (config.keepFirstLetter && firstLetters.length() > 0 && !processedFirstLetter) {
                processedFirstLetter = true;
                String fl;
                if (firstLetters.length() > config.LimitFirstLetterLength && config.LimitFirstLetterLength > 0) {
                    fl = firstLetters.substring(0, config.LimitFirstLetterLength);
                } else {
                    fl = firstLetters.toString();
                }
                if (config.lowercase) {
                    fl = fl.toLowerCase();
                }
                if (!(config.keepSeparateFirstLetter && fl.length() <= 1)) {
                    addCandidate(new TermItem(fl, 0, fl.length(), 0));
                }
            }

            if (!processedSortCandidate) {
                processedSortCandidate = true;
                Collections.sort(candidate);
            }

            if (candidateOffset < candidate.size()) {
                TermItem item = candidate.get(candidateOffset);
                candidateOffset++;
                setTerm(item.term, item.startOffset, item.endOffset, item.position + 1);
                return true;
            }


            done = true;
            return false;
        }
        return false;
    }

    private int parseBuff(StringBuilder buff, int buffSize, int buffPosition) {
        if (config.keepNoneChinese) {
            if (config.noneChinesePinyinTokenize) {
                List<String> result = PinyinAlphabetTokenizer.walk(buff.toString());
                int start = (lastPosition - buffSize + 1);
                for (int i = 0; i < result.size(); i++) {
                    int end;
                    String t = result.get(i);
                    if (config.fixedPinyinOffset) {
                        end = start + 1;
                    } else {
                        end = start + t.length();
                    }
                    addCandidate(new TermItem(result.get(i), start, end, buffPosition));
                    start = end;
                }
            } else if (config.keepFirstLetter || config.keepSeparateFirstLetter || config.keepFullPinyin || !config.keepNoneChineseInJoinedFullPinyin) {
                addCandidate(new TermItem(buff.toString(), lastPosition - buffSize, lastPosition, buffPosition));
            }
        }

        buff.setLength(0);
        buffSize = 0;
        return buffSize;
    }

    @Override
    public final void end() throws IOException {
        super.end();
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        position = 0;
        candidateOffset = 0;
        this.done = false;
        this.processedCandidate = false;
        this.processedFirstLetter = false;
        this.processedFullPinyinLetter = false;
        this.processedOriginal = false;
        firstLetters.setLength(0);
        fullPinyinLetters.setLength(0);
        termsFilter.clear();
        candidate.clear();
        source = null;
        lastIncrementPosition = 0;
    }


}
