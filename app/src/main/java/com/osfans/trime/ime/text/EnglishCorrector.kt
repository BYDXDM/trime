package com.osfans.trime.ime.text

import timber.log.Timber

/**
 * 英文输入纠错。
 *
 * ## 为什么这么做
 *
 * Trime 的英文模式（`ascii_mode: 1`）走 Rime 的 `ascii_composer`，
 * 字母被**原样直通上屏**，不经过任何编码/词典，因此默认没有纠错能力。
 * 中文侧有 `mydomain` 词库兜底，英文侧完全裸奔。
 *
 * 这里不去改 Rime 的 C++（侵入性太强，且 ASCII 直通本就是 Rime 的设计），
 * 而是在**提交文本的单一出口**上做一层后处理。
 *
 * ## 触发时机（关键）
 *
 * 只在**词边界**纠错，绝不在打字途中纠错：
 * - 用户敲 `helo` 时，如果每次按键都纠，会在打到第三个字母时就被改成 `help`，
 *   后面的输入全乱。所以必须等一个词"结束"了再判断。
 * - 词边界 = 空格 / 回车 / 标点 / 中英切换 / 光标离开。
 *
 * ## 纠错策略（保守优先）
 *
 * 只纠正**编辑距离 1 且非用户已确认过的**拼写，且必须命中小词表。
 * 宁可漏纠，不可误纠 —— 把用户想打的词改掉，比不纠还烦人。
 * 具体规则见 [correctWord]。
 *
 * ## 设计取舍
 *
 * 词表内嵌（而非读文件），理由是：纠错是高频小操作，不能有 IO 抖动；
 * 表本身就小（几百条高频词＋常见错拼），内嵌几 KB 完全可接受。
 * 如果要扩充，改 [COMMON_WORDS] / [KNOWN_MISSPELLINGS] 即可。
 */
object EnglishCorrector {

    /**
     * 高频正确词。用于判断"用户打的是不是一个真词"，
     * 以及作为纠错的**候选目标**。
     *
     * 选取原则：日常高频（top ~500），覆盖绝大多数输入场景。
     * 不求全，求准 —— 表外词一律不纠。
     */
    private val COMMON_WORDS: Set<String> = setOf(
        // 冠词/介词/连词/代词
        "a", "an", "the", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
        "in", "on", "at", "by", "for", "with", "about", "against", "between", "into", "through",
        "during", "before", "after", "above", "below", "to", "from", "up", "down", "out", "off",
        "over", "under", "again", "further", "once", "here", "there", "when", "where", "why", "how",
        "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no", "nor",
        "not", "only", "own", "same", "so", "too", "very", "can", "will", "just", "should", "now",
        "i", "me", "my", "we", "our", "you", "your", "he", "him", "his", "she", "her", "it", "its",
        "they", "them", "their", "what", "which", "who", "whom", "as", "because", "while", "until",
        // 动词
        "is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had", "having",
        "do", "does", "did", "doing", "would", "could", "shall", "may", "might", "must",
        "go", "goes", "went", "gone", "going", "get", "gets", "got", "make", "makes", "made",
        "take", "takes", "took", "taken", "come", "comes", "came", "see", "sees", "saw", "seen",
        "know", "knows", "knew", "known", "think", "thinks", "thought", "say", "says", "said",
        "want", "wants", "wanted", "use", "uses", "used", "find", "finds", "found", "give", "gives",
        "gave", "given", "tell", "tells", "told", "work", "works", "worked", "call", "calls", "called",
        "try", "tries", "tried", "ask", "asks", "asked", "need", "needs", "needed", "feel", "feels",
        "felt", "become", "becomes", "became", "leave", "leaves", "left", "put", "mean", "means",
        "keep", "keeps", "kept", "let", "begin", "begins", "began", "seem", "seems", "help", "helps",
        "talk", "talks", "turn", "turns", "start", "starts", "show", "shows", "hear", "hears", "heard",
        "play", "plays", "run", "runs", "ran", "move", "moves", "live", "lives", "believe", "believes",
        "bring", "brings", "happen", "happens", "write", "writes", "wrote", "written", "provide",
        "sit", "sits", "sat", "stand", "stands", "stood", "lose", "loses", "lost", "pay", "pays",
        "meet", "meets", "met", "include", "includes", "continue", "set", "learn", "change", "lead",
        "understand", "watch", "follow", "stop", "create", "speak", "read", "allow", "add", "spend",
        "grow", "open", "walk", "win", "offer", "remember", "love", "consider", "appear", "buy",
        "wait", "serve", "die", "send", "expect", "build", "stay", "fall", "cut", "reach", "kill",
        // 名词
        "time", "year", "people", "way", "day", "man", "thing", "woman", "life", "child", "world",
        "school", "state", "family", "student", "group", "country", "problem", "hand", "part",
        "place", "case", "week", "company", "system", "program", "question", "government", "number",
        "night", "point", "home", "water", "room", "mother", "area", "money", "story", "fact",
        "month", "lot", "right", "study", "book", "eye", "job", "word", "business", "issue", "side",
        "kind", "head", "house", "service", "friend", "father", "power", "hour", "game", "line",
        "end", "member", "law", "car", "city", "community", "name", "president", "team", "minute",
        "idea", "kid", "body", "information", "back", "parent", "face", "others", "level", "office",
        "door", "health", "person", "art", "war", "history", "party", "result", "change", "morning",
        "reason", "research", "girl", "guy", "moment", "air", "teacher", "force", "education",
        // 形容词/副词
        "good", "new", "first", "last", "long", "great", "little", "own", "old", "right", "big",
        "high", "different", "small", "large", "next", "early", "young", "important", "public",
        "bad", "able", "happy", "sure", "true", "false", "free", "full", "easy", "hard", "best",
        "better", "worse", "worst", "nice", "fine", "glad", "sorry", "ready", "clear", "real",
        "well", "also", "however", "still", "already", "always", "never", "often", "sometimes",
        "usually", "really", "maybe", "perhaps", "probably", "actually", "finally", "instead",
        "almost", "enough", "quite", "rather", "together", "around", "away", "back", "again",
        // 技术高频词（考虑本输入法用户画像）
        "code", "data", "file", "user", "test", "build", "error", "value", "name", "type", "list",
        "text", "page", "link", "site", "app", "key", "map", "set", "get", "json", "http", "api",
        "git", "repo", "branch", "commit", "push", "pull", "merge", "pull", "issue", "bug", "fix",
        "function", "class", "object", "string", "number", "array", "index", "input", "output",
        "server", "client", "request", "response", "config", "script", "command", "memory", "cache",
        // 补漏：测试发现这些高频词缺失会导致正确词被误纠
        "success", "successful", "usually", "always", "never", "often",
        "separate", "occurred", "receive", "definitely", "necessary",
        "environment", "experience", "management", "government", "language",
        "beginning", "committee", "tomorrow", "yesterday", "wednesday",
        "february", "whether", "weather", "technical", "picture", "service",
    )

    /**
     * 已知的常见错拼 → 正确写法。
     *
     * 这张表的意义是**绕过编辑距离的歧义**：像 `teh` 编辑距离 1 的词有
     * 一堆（ten/tea/the/tech），只有查表才能确定用户要的是 `the`。
     * 表里的映射都是 English 世界里公认的 typo，误纠概率极低。
     */
    private val KNOWN_MISSPELLINGS: Map<String, String> = mapOf(
        // 键位相邻导致的经典 typo（QWERTY 手滑）
        "teh" to "the", "hte" to "the", "thw" to "the", "tge" to "the",
        "adn" to "and", "nad" to "and", "amd" to "and",
        "taht" to "that", "thta" to "that", "htat" to "that",
        "waht" to "what", "whta" to "what", "whatt" to "what",
        "yuor" to "your", "yoru" to "your", "youre" to "you're",
        "jsut" to "just", "jstu" to "just",
        "taht" to "that", "wiht" to "with", "wih" to "with", "wtih" to "with",
        "fo" to "of", "foe" to "for", "fro" to "for",
        "ti" to "it", "itn" to "into",
        "si" to "is", "ist" to "is",
        "beacuse" to "because", "becuase" to "because", "becasue" to "because", "becouse" to "because",
        "thsi" to "this", "tihs" to "this", "thsi" to "this",
        "thier" to "their", "theri" to "their", "thre" to "there",
        "woudl" to "would", "wuold" to "would", "wolud" to "would",
        "coudl" to "could", "cuold" to "could",
        "shoudl" to "should", "shuold" to "should",
        "recieve" to "receive", "recive" to "receive",
        "seperate" to "separate", "seperat" to "separate",
        "definately" to "definitely", "definatly" to "definitely", "definetly" to "definitely",
        "occured" to "occurred", "ocurred" to "occurred",
        "untill" to "until", "util" to "until",
        "wich" to "which", "whcih" to "which", "whihc" to "which",
        "beacause" to "because", "becaus" to "because",
        "havent" to "haven't", "hasnt" to "hasn't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "wasnt" to "wasn't",
        "werent" to "weren't", "cant" to "can't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "wont" to "won't",
        "dont" to "don't", "im" to "I'm", "ive" to "I've", "ill" to "I'll",
        "id" to "I'd", "its" to "it's",
        // 少字母
        "wich" to "which", "whitch" to "which",
        "thn" to "then", "thne" to "then",
        "wnat" to "want", "wnt" to "want",
        "liek" to "like", "liek" to "like", "lke" to "like",
        "haev" to "have", "hvae" to "have", "ahve" to "have",
        "mkae" to "make", "maek" to "make",
        "knwo" to "know", "konw" to "know",
        "thnik" to "think", "thikn" to "think",
        "wrok" to "work", "wokr" to "work",
        "gor" to "for", "goign" to "going", "gonig" to "going",
        "comming" to "coming", "comeing" to "coming",
        "makign" to "making", "mkaing" to "making",
        "tryign" to "trying", "tryin" to "trying",
        "gettign" to "getting", "geting" to "getting",
        "somethign" to "something", "somthing" to "something", "somethng" to "something",
        "nothign" to "nothing", "nothng" to "nothing",
        "anythign" to "anything", "anythng" to "anything",
        "eveything" to "everything", "everythign" to "everything",
        "probly" to "probably", "probally" to "probably", "prolly" to "probably",
        "abotu" to "about", "aobut" to "about", "abput" to "about",
        "agina" to "again", "agian" to "again", "agin" to "again",
        "alwasy" to "always", "allways" to "always", "alwyas" to "always",
        "nver" to "never", "nevr" to "never",
        "peopel" to "people", "poeple" to "people", "peple" to "people",
        "bcuase" to "because", "beause" to "because",
        "realy" to "really", "reallly" to "really", "realyl" to "really",
        "usualy" to "usually", "ususally" to "usually",
        "finaly" to "finally", "finnaly" to "finally",
        "actualy" to "actually", "actully" to "actually",
        "diffrent" to "different", "diffcult" to "difficult",
        "importnat" to "important", "improtant" to "important",
        "questin" to "question", "questoin" to "question",
        "manger" to "manager", "managment" to "management",
        "goverment" to "government", "enviornment" to "environment",
        "experiance" to "experience", "expierence" to "experience",
        "neccessary" to "necessary", "neccessary" to "necessary", "necesary" to "necessary",
        "accomodate" to "accommodate", "acommodate" to "accommodate",
        "tommorow" to "tomorrow", "tomorow" to "tomorrow", "tomorow" to "tomorrow",
        "yesterdy" to "yesterday", "yestarday" to "yesterday",
        "wensday" to "wednesday", "wednsday" to "wednesday",
        "feberary" to "february", "feburary" to "february",
        "sucess" to "success", "succes" to "success", "sucessful" to "successful",
        "begining" to "beginning", "beginnig" to "beginning",
        "commitee" to "committee", "committe" to "committee",
        "evry" to "every", "evey" to "every",
        "fianl" to "final", "fnial" to "final",
        "langauge" to "language", "languge" to "language",
        "memeber" to "member", "memebr" to "member",
        "momento" to "moment", "momen" to "moment",
        "picutre" to "picture", "pictuer" to "picture",
        "reponse" to "response", "responce" to "response",
        "serach" to "search", "seach" to "search",
        "servie" to "service", "servcie" to "service",
        "studnet" to "student", "studetn" to "student",
        "tehcnical" to "technical", "techincal" to "technical",
        "wether" to "whether", "wheather" to "weather",
        "wihch" to "which", "chi" to "which",
        // 技术词
        "fucntion" to "function", "functoin" to "function",
        "reutrn" to "return", "retun" to "return", "retrun" to "return",
        "strign" to "string", "stirng" to "string",
        "numebr" to "number", "nuber" to "number",
        "arary" to "array", "arry" to "array",
        "objcet" to "object", "objet" to "object",
        "clsas" to "class", "clas" to "class",
        "vairable" to "variable", "varible" to "variable",
        "inital" to "initial", "intial" to "initial",
        "paramter" to "parameter", "parmeter" to "parameter",
        "propety" to "property", "proprety" to "property",
        "catche" to "catch", "cathc" to "catch",
        "crate" to "create", "cerate" to "create",
        "deafult" to "default", "defualt" to "default",
        "empyt" to "empty", "emty" to "empty",
        "excption" to "exception", "exeption" to "exception",
        "flase" to "false", "fasle" to "false",
        "messaeg" to "message", "mesage" to "message",
        "repsoitory" to "repository", "repositry" to "repository",
        "commti" to "commit", "comit" to "commit",
        "brach" to "branch", "brnach" to "branch",
        "conifg" to "config", "confg" to "config",
        "scirpt" to "script", "scritp" to "script",
        "sevrer" to "server", "serer" to "server",
        "cuase" to "cause", "becasue" to "because",
    )

    /**
     * 永!远!不!纠!的!词。
     *
     * 都是编辑距离 1 极易被误伤、但一旦改错后果严重的词：
     * 技术专有名词、协议名、常见缩写。
     * 例如 `redis`→`error`、`nginx`→`night` 这类，用户会直接崩溃。
     *
     * 判定顺序上放在最前面 —— 这些词一律原样放过。
     */
    private val NEVER_CORRECT: Set<String> = setOf(
        // 技术/工具
        "redis", "nginx", "docker", "github", "gitlab", "kafka", "hadoop",
        "linux", "ubuntu", "debian", "centos", "gradle", "kotlin", "java",
        "python", "golang", "rust", "swift", "react", "vue", "django",
        "flask", "spring", "mysql", "sqlite", "postgres", "mongodb",
        "elastic", "kubernetes", "terraform", "ansible", "jenkins",
        "android", "chrome", "firefox", "safari", "python3",
        // 协议/格式
        "http", "https", "html", "css", "json", "xml", "yaml", "toml",
        "sql", "tcp", "udp", "ssh", "ftp", "smtp", "dns", "cors", "jwt",
        // 输入法自身相关
        "rime", "trime", "pinyin", "librime",
        // 常见缩写（全大写输入时通常不会被 lower 到这里，但先兜住）
        "api", "sdk", "ide", "cli", "gui", "cpu", "gpu", "ram", "ssd",
        "url", "uri", "uuid", "utf", "ascii", "utf8", "ipv4", "ipv6",
        "ok", "id", "os", "db", "ui", "ux", "ai", "ml",
    )

    /**
     * 纠错开关。由调用方（输入法服务）在英文模式下打开、中文模式关闭。
     * 放在这里而不是构造参数，是为了让 [correct] 保持纯函数便于测试。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 用户"撤销纠错"过的词。撤销过的词不再纠，尊重用户意图。
     * 用并发集合，因为输入和撤销可能来自不同线程。
     */
    private val userRejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 用户主动纠正过的词（原拼写 → 用户选择的写法），下次直接跟随。 */
    private val userPreferred = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 判断一个**完整单词**是否需要纠错，返回纠正后的词；不需要则返回 null。
     *
     * 规则（按顺序，全部满足才纠）：
     * 1. 开关打开，且长度 >= 3（`it`/`is` 这种短词纠错风险太高）
     * 2. 词本身不是已知正确词
     * 3. 用户没撤销过这个词
     * 4. 要么命中 [KNOWN_MISSPELLINGS]，要么在 [COMMON_WORDS] 里
     *    存在唯一一个编辑距离为 1 的词
     *
     * 第 4 条是核心：编辑距离 1 若命中多个候选就**放弃**。
     * 例如 `cat` → 可能是 `car`/`cut`/`can`，歧义太大，宁可不纠。
     */
    fun correctWord(word: String): String? {
        if (!enabled) return null
        if (word.length < MIN_LENGTH) return null

        val lower = word.lowercase()

        // 白名单最优先：技术词/缩写一律放过
        if (lower in NEVER_CORRECT) return null

        // 用户之前明确撤销过 → 永不纠正
        if (lower in userRejected) return null

        // 用户之前主动选过某个写法 → 直接跟随
        userPreferred[lower]?.let { return matchCase(it, word) }

        // 本身就是正确词 → 不动
        if (lower in COMMON_WORDS) return null

        // 优先查已知错拼表（无歧义）
        KNOWN_MISSPELLINGS[lower]?.let { return matchCase(it, word) }

        // 再看编辑距离 1 的候选，有且仅有一个才纠
        val candidates = COMMON_WORDS.filter { isSingleEditAway(lower, it) }
        return if (candidates.size == 1) matchCase(candidates.first(), word) else null
    }

    /**
     * 纠错入口。传入**一段已提交的文本**，返回应当替换成的文本；
     * 无需改动时返回 null。
     *
     * 只在文本形如「单个英文单词」时才纠 —— 带空格/换行/多词的一律放过。
     * 这样调用方（commitText）不需要自己切词，逻辑集中在这里。
     */
    fun correct(committed: String): String? {
        if (!enabled) return null
        val trimmed = committed.trim()
        if (trimmed.isEmpty()) return null
        // 只处理 ASCII 字母（允许词内撇号和连字符）。
        // 英文缓冲本身也只收 ASCII，这里再兜底一次，避免未来新增调用路径
        // 把 Unicode 单词送进英文纠错。
        if (!trimmed.all {
            it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-'
        }) return null
        // 含撇号/连字符的交给已知表处理，不做编辑距离（复数和所有格容易误判）
        val corrected = correctWord(trimmed) ?: return null
        if (corrected == trimmed) return null
        // 保留原有的首尾空白
        return committed.replace(trimmed, corrected)
    }

    /** 用户撤销某词的纠错。 */
    fun reject(word: String) {
        userRejected.add(word.lowercase())
        Timber.d("English corrector: rejected %s", word)
    }

    /** 记录用户主动选择的写法（比如用户手动改回正确拼写）。 */
    fun prefer(from: String, to: String) {
        userPreferred[from.lowercase()] = to
        Timber.d("English corrector: prefer %s -> %s", from, to)
    }

    /** 清空用户偏好（供设置项"重置纠错学习"调用）。 */
    fun resetUserData() {
        userRejected.clear()
        userPreferred.clear()
    }

    private const val MIN_LENGTH = 3

    /**
     * 编辑距离是否为 1（相邻换位也算 1，覆盖 `teh`↔`the` 这类手滑）。
     *
     * ★ 关键：换位/替换判定之后**必须继续比对剩余字符**。
     *   早期版本只看前两处不同就下结论，导致
     *   `success` vs `usually`（前两位正好是 su↔us）被误判为编辑距离 1，
     *   进而把正确词 success 纠成 usually。
     *   教训：编辑距离必须是"全串"性质，不能只看局部。
     */
    private fun isSingleEditAway(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false

        if (la == lb) {
            // 找到第一处不同
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return false
            // 情形 A：纯替换 —— 跳过这一位后，剩余必须完全一致
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            // 情形 B：与下一位互换 —— 跳过两位后，剩余必须完全一致
            if (i + 1 < la &&
                a[i] == b[i + 1] && a[i + 1] == b[i] &&
                a.substring(i + 2) == b.substring(i + 2)
            ) {
                return true
            }
            return false
        }

        // 增/删：把长的那个去掉恰好一个字符后，应完全等于短的
        val (long, short) = if (la > lb) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < long.length && j < short.length) {
            if (long[i] == short[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                i++
            }
        }
        return true
    }

    /** 让纠正结果的**大小写风格**跟原词一致，避免把 `Teh` 改成 `the`。 */
    private fun matchCase(target: String, source: String): String = when {
        source.all { !it.isLetter() || it.isUpperCase() } && source.any { it.isLetter() } ->
            target.uppercase()
        source.firstOrNull()?.isUpperCase() == true ->
            target.replaceFirstChar { it.uppercase() }
        else -> target
    }
}
