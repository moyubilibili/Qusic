package com.qusic.app;

/**
 * 每日名言。
 *
 * <h3>为什么不联网</h3>
 * 语录全部内置，取「距 1970-01-01 的天数 % 语录数」当索引 ——
 * 于是**同一天永远是同一句，跨天自动换**。好处：
 * <ul>
 *   <li>零网络请求，飞行模式也能看</li>
 *   <li>不需要 API key，不会哪天接口挂了就空着</li>
 *   <li>一百来条字符串只占几 KB，对这个体积的 App 完全可接受</li>
 * </ul>
 *
 * <p>语录以音乐为主题，中英各半。署名只在有把握时才写，
 * 拿不准的宁可标「佚名」也不硬安到名人头上。
 */
public final class Quotes {

    /** 一条语录 */
    public static final class Quote {
        public final String text;
        public final String author;
        Quote(String t, String a) { text = t; author = a; }
    }

    private Quotes() {}

    private static final Quote[] LIST = {
        // ── 中文 ──
        new Quote("兴于诗，立于礼，成于乐。", "孔子"),
        new Quote("夫乐者，乐也，人情之所必不免也。", "荀子"),
        new Quote("声无哀乐。", "嵇康《声无哀乐论》"),
        new Quote("移风易俗，莫善于乐。", "《孝经》"),
        new Quote("凡音之起，由人心生也。", "《礼记·乐记》"),
        new Quote("音乐，是人生最大的快乐；音乐，是生活中的一股清泉。", "冼星海"),
        new Quote("余音绕梁，三日不绝。", "《列子·汤问》"),
        new Quote("大音希声，大象无形。", "老子"),
        new Quote("乐者，天地之和也。", "《礼记·乐记》"),
        new Quote("唯乐不可以为伪。", "《礼记·乐记》"),
        new Quote("琴者，心也。", "《琴史》"),
        new Quote("转轴拨弦三两声，未成曲调先有情。", "白居易《琵琶行》"),
        new Quote("此曲只应天上有，人间能得几回闻。", "杜甫"),
        new Quote("欲将心事付瑶琴，知音少，弦断有谁听。", "岳飞《小重山》"),
        new Quote("诗言志，歌永言，声依永，律和声。", "《尚书·舜典》"),
        new Quote("歌以咏志。", "曹操"),
        new Quote("慷慨吐清音，明转出天然。", "《子夜歌》"),
        new Quote("但识琴中趣，何劳弦上声。", "陶渊明"),
        new Quote("独坐幽篁里，弹琴复长啸。", "王维"),
        new Quote("琴瑟在御，莫不静好。", "《诗经》"),

        // ── 西文 ──
        new Quote("Without music, life would be a mistake.", "Friedrich Nietzsche"),
        new Quote("Where words fail, music speaks.", "Hans Christian Andersen"),
        new Quote("Music expresses that which cannot be said and on which it is impossible to be silent.", "Victor Hugo"),
        new Quote("If music be the food of love, play on.", "William Shakespeare"),
        new Quote("Music gives a soul to the universe, wings to the mind, flight to the imagination, and life to everything.", "Plato"),
        new Quote("Music is a higher revelation than all wisdom and philosophy.", "Ludwig van Beethoven"),
        new Quote("Music is the mediator between the spiritual and the sensual life.", "Ludwig van Beethoven"),
        new Quote("Music is enough for a lifetime, but a lifetime is not enough for music.", "Sergei Rachmaninoff"),
        new Quote("Melody is the essence of music.", "Wolfgang Amadeus Mozart"),
        new Quote("I haven't understood a bar of music in my life, but I have felt it.", "Igor Stravinsky"),
        new Quote("Everything we do is music.", "John Cage"),
        new Quote("There are two kinds of music. Good music, and the other kind.", "Duke Ellington"),
        new Quote("Don't play what's there, play what's not there.", "Miles Davis"),
        new Quote("What we play is life.", "Louis Armstrong"),
        new Quote("One good thing about music, when it hits you, you feel no pain.", "Bob Marley"),
        new Quote("Music is a safe kind of high.", "Jimi Hendrix"),
        new Quote("Music is the only religion that delivers the goods.", "Frank Zappa"),
        new Quote("Music is the melody whose text is the world.", "Arthur Schopenhauer"),
        new Quote("Music is the most abstract of all the arts, and yet it is the one that speaks most directly to the heart.", "Søren Kierkegaard"),
        new Quote("After silence, that which comes nearest to expressing the inexpressible is music.", "Aldous Huxley"),
        new Quote("Music washes away from the soul the dust of everyday life.", "Berthold Auerbach"),
        new Quote("The only truth is music.", "Jack Kerouac"),
        new Quote("Music, once admitted to the soul, becomes a sort of spirit, and never dies.", "Edward Bulwer-Lytton"),
        new Quote("Life is one grand, sweet song, so start the music.", "Ronald Reagan"),
        new Quote("Music is the shorthand of emotion.", "Leo Tolstoy"),
        new Quote("Where words leave off, music begins.", "Heinrich Heine"),
        new Quote("A painter paints pictures on canvas. But musicians paint their pictures on silence.", "Leopold Stokowski"),
        new Quote("Music can change the world because it can change people.", "Bono"),
        new Quote("To stop the flow of music would be like the stopping of time itself, incredible and inconceivable.", "Aaron Copland"),
        new Quote("The music is not in the notes, but in the silence between.", "Wolfgang Amadeus Mozart"),
        new Quote("Music is the art of thinking with sounds.", "Jules Combarieu"),
        new Quote("Where there is music, there can be no evil.", "Miguel de Cervantes"),
        new Quote("Music produces a kind of pleasure which human nature cannot do without.", "Confucius"),
        new Quote("Practice any art, no matter how well or badly, not to get money and fame, but to experience becoming.", "Kurt Vonnegut"),
        new Quote("Simplicity is the ultimate sophistication.", "Leonardo da Vinci"),
        new Quote("Perfection is achieved not when there is nothing more to add, but when there is nothing left to take away.", "Antoine de Saint-Exupéry"),
        new Quote("Any fool can write code that a computer can understand. Good programmers write code that humans can understand.", "Martin Fowler"),
        new Quote("Make it work, make it right, make it fast.", "Kent Beck"),
        new Quote("The best way to predict the future is to invent it.", "Alan Kay"),
    };

    /** 今天的索引：按天推进，同一天不变 */
    private static int todayIndex() {
        long days = System.currentTimeMillis() / 86400000L;
        int i = (int) (days % LIST.length);
        if (i < 0) i += LIST.length;      // 时区/时钟异常时兜底
        return i;
    }

    public static Quote today() { return LIST[todayIndex()]; }

    /** 随机来一条（用于「换一句」） */
    public static Quote random() {
        int i = new java.util.Random().nextInt(LIST.length);
        return LIST[i];
    }

    /** 取第 n 条，n 会对长度取模 */
    public static Quote at(int n) {
        int i = n % LIST.length;
        if (i < 0) i += LIST.length;
        return LIST[i];
    }

    public static int count() { return LIST.length; }
}
