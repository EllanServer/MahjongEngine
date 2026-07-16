#include <jni.h>

#include "fan.h"
#include "handtiles.h"
#include "pack.h"
#include "tile.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

namespace {

struct JsonValue;
using JsonObject = std::map<std::string, JsonValue>;
using JsonArray = std::vector<JsonValue>;

struct JsonValue {
    using Storage = std::variant<std::nullptr_t, bool, std::int64_t, std::string, JsonArray, JsonObject>;
    Storage value;

    JsonValue() : value(nullptr) {}
    JsonValue(std::nullptr_t) : value(nullptr) {}
    JsonValue(bool input) : value(input) {}
    JsonValue(std::int64_t input) : value(input) {}
    JsonValue(std::string input) : value(std::move(input)) {}
    JsonValue(const char* input) : value(std::string(input)) {}
    JsonValue(JsonArray input) : value(std::move(input)) {}
    JsonValue(JsonObject input) : value(std::move(input)) {}
};

class JsonParser {
  public:
    explicit JsonParser(std::string source) : source_(std::move(source)) {}

    JsonValue parse() {
        skipWhitespace();
        JsonValue value = parseValue();
        skipWhitespace();
        if (index_ != source_.size()) {
            throw std::runtime_error("Unexpected trailing JSON content.");
        }
        return value;
    }

  private:
    JsonValue parseValue() {
        skipWhitespace();
        if (index_ >= source_.size()) {
            throw std::runtime_error("Unexpected end of JSON.");
        }
        char current = source_[index_];
        switch (current) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return JsonValue(parseString());
            case 't':
                consumeLiteral("true");
                return JsonValue(true);
            case 'f':
                consumeLiteral("false");
                return JsonValue(false);
            case 'n':
                consumeLiteral("null");
                return JsonValue(nullptr);
            default:
                if (current == '-' || std::isdigit(static_cast<unsigned char>(current))) {
                    return JsonValue(parseNumber());
                }
                throw std::runtime_error("Invalid JSON value.");
        }
    }

    JsonValue parseObject() {
        expect('{');
        JsonObject object;
        skipWhitespace();
        if (peek('}')) {
            expect('}');
            return JsonValue(std::move(object));
        }
        while (true) {
            std::string key = parseString();
            skipWhitespace();
            expect(':');
            object.emplace(std::move(key), parseValue());
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return JsonValue(std::move(object));
            }
            expect(',');
        }
    }

    JsonValue parseArray() {
        expect('[');
        JsonArray array;
        skipWhitespace();
        if (peek(']')) {
            expect(']');
            return JsonValue(std::move(array));
        }
        while (true) {
            array.push_back(parseValue());
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return JsonValue(std::move(array));
            }
            expect(',');
        }
    }

    std::string parseString() {
        expect('"');
        std::string result;
        while (index_ < source_.size()) {
            char current = source_[index_++];
            if (current == '"') {
                return result;
            }
            if (current != '\\') {
                result.push_back(current);
                continue;
            }
            if (index_ >= source_.size()) {
                throw std::runtime_error("Invalid JSON escape.");
            }
            char escaped = source_[index_++];
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    result.push_back(escaped);
                    break;
                case 'b':
                    result.push_back('\b');
                    break;
                case 'f':
                    result.push_back('\f');
                    break;
                case 'n':
                    result.push_back('\n');
                    break;
                case 'r':
                    result.push_back('\r');
                    break;
                case 't':
                    result.push_back('\t');
                    break;
                case 'u':
                    throw std::runtime_error("Unicode escapes are not supported by this minimal parser.");
                default:
                    throw std::runtime_error("Invalid JSON escape.");
            }
        }
        throw std::runtime_error("Unterminated JSON string.");
    }

    std::int64_t parseNumber() {
        std::size_t start = index_;
        if (source_[index_] == '-') {
            ++index_;
        }
        while (index_ < source_.size() && std::isdigit(static_cast<unsigned char>(source_[index_]))) {
            ++index_;
        }
        return std::stoll(source_.substr(start, index_ - start));
    }

    void skipWhitespace() {
        while (index_ < source_.size() && std::isspace(static_cast<unsigned char>(source_[index_]))) {
            ++index_;
        }
    }

    void consumeLiteral(const char* literal) {
        std::size_t length = std::char_traits<char>::length(literal);
        if (source_.compare(index_, length, literal) != 0) {
            throw std::runtime_error("Invalid JSON literal.");
        }
        index_ += length;
    }

    void expect(char expected) {
        skipWhitespace();
        if (index_ >= source_.size() || source_[index_] != expected) {
            throw std::runtime_error("Unexpected JSON token.");
        }
        ++index_;
    }

    bool peek(char expected) {
        skipWhitespace();
        return index_ < source_.size() && source_[index_] == expected;
    }

    std::string source_;
    std::size_t index_ = 0;
};

const JsonObject& asObject(const JsonValue& value) {
    return std::get<JsonObject>(value.value);
}

const JsonArray& asArray(const JsonValue& value) {
    return std::get<JsonArray>(value.value);
}

const std::string& asString(const JsonValue& value) {
    return std::get<std::string>(value.value);
}

bool asBool(const JsonValue& value) {
    return std::get<bool>(value.value);
}

int asInt(const JsonValue& value) {
    return static_cast<int>(std::get<std::int64_t>(value.value));
}

std::optional<std::string> optionalString(const JsonObject& object, const std::string& key) {
    auto iterator = object.find(key);
    if (iterator == object.end() || std::holds_alternative<std::nullptr_t>(iterator->second.value)) {
        return std::nullopt;
    }
    return asString(iterator->second);
}

std::vector<std::string> stringArray(const JsonObject& object, const std::string& key) {
    std::vector<std::string> values;
    auto iterator = object.find(key);
    if (iterator == object.end()) {
        return values;
    }
    for (const JsonValue& entry : asArray(iterator->second)) {
        values.push_back(asString(entry));
    }
    return values;
}

std::string jsonEscape(const std::string& input) {
    std::string escaped;
    escaped.reserve(input.size() + 8);
    for (char character : input) {
        switch (character) {
            case '\\':
                escaped += "\\\\";
                break;
            case '"':
                escaped += "\\\"";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                escaped.push_back(character);
                break;
        }
    }
    return escaped;
}

std::string jsonString(const std::string& input) {
    return "\"" + jsonEscape(input) + "\"";
}

struct MeldInput {
    std::string type;
    std::vector<std::string> tiles;
    std::optional<std::string> claimedTile;
    std::optional<std::string> fromSeat;
    bool open = true;
};

struct SeatPointsInput {
    std::string seat;
    int points = 0;
};

struct FanRequest {
    std::vector<std::string> handTiles;
    std::vector<MeldInput> melds;
    std::string winningTile;
    std::string winType;
    std::optional<std::string> seatWind;
    std::optional<std::string> roundWind;
    std::vector<std::string> flowerTiles;
    std::vector<std::string> flags;
};

struct TingRequest {
    std::vector<std::string> handTiles;
    std::vector<MeldInput> melds;
    std::optional<std::string> seatWind;
    std::optional<std::string> roundWind;
    std::vector<std::string> flowerTiles;
    std::vector<std::string> flags;
};

struct WinRequest {
    std::vector<std::string> handTiles;
    std::vector<MeldInput> melds;
    std::string winningTile;
    std::string winType;
    std::string winnerSeat;
    std::optional<std::string> discarderSeat;
    std::optional<std::string> seatWind;
    std::optional<std::string> roundWind;
    std::vector<SeatPointsInput> seatPoints;
    std::vector<std::string> flowerTiles;
    std::vector<std::string> flags;
};

struct FanEntry {
    std::string name;
    int fan = 0;
    int count = 0;
};

struct ScoreDelta {
    std::string seat;
    int delta = 0;
};

FanRequest parseFanRequest(const std::string& payload) {
    JsonObject root = asObject(JsonParser(payload).parse());
    std::vector<MeldInput> melds;
    auto meldIt = root.find("melds");
    if (meldIt != root.end()) {
        for (const JsonValue& entry : asArray(meldIt->second)) {
            const JsonObject& object = asObject(entry);
            melds.push_back(MeldInput{
                asString(object.at("type")),
                stringArray(object, "tiles"),
                optionalString(object, "claimedTile"),
                optionalString(object, "fromSeat"),
                object.contains("open") ? asBool(object.at("open")) : true
            });
        }
    }
    return FanRequest{
        stringArray(root, "handTiles"),
        std::move(melds),
        asString(root.at("winningTile")),
        asString(root.at("winType")),
        optionalString(root, "seatWind"),
        optionalString(root, "roundWind"),
        stringArray(root, "flowerTiles"),
        stringArray(root, "flags")
    };
}

TingRequest parseTingRequest(const std::string& payload) {
    JsonObject root = asObject(JsonParser(payload).parse());
    std::vector<MeldInput> melds;
    auto meldIt = root.find("melds");
    if (meldIt != root.end()) {
        for (const JsonValue& entry : asArray(meldIt->second)) {
            const JsonObject& object = asObject(entry);
            melds.push_back(MeldInput{
                asString(object.at("type")),
                stringArray(object, "tiles"),
                optionalString(object, "claimedTile"),
                optionalString(object, "fromSeat"),
                object.contains("open") ? asBool(object.at("open")) : true
            });
        }
    }
    return TingRequest{
        stringArray(root, "handTiles"),
        std::move(melds),
        optionalString(root, "seatWind"),
        optionalString(root, "roundWind"),
        stringArray(root, "flowerTiles"),
        stringArray(root, "flags")
    };
}

WinRequest parseWinRequest(const std::string& payload) {
    JsonObject root = asObject(JsonParser(payload).parse());
    std::vector<MeldInput> melds;
    auto meldIt = root.find("melds");
    if (meldIt != root.end()) {
        for (const JsonValue& entry : asArray(meldIt->second)) {
            const JsonObject& object = asObject(entry);
            melds.push_back(MeldInput{
                asString(object.at("type")),
                stringArray(object, "tiles"),
                optionalString(object, "claimedTile"),
                optionalString(object, "fromSeat"),
                object.contains("open") ? asBool(object.at("open")) : true
            });
        }
    }
    std::vector<SeatPointsInput> seatPoints;
    auto seatPointsIt = root.find("seatPoints");
    if (seatPointsIt != root.end()) {
        for (const JsonValue& entry : asArray(seatPointsIt->second)) {
            const JsonObject& object = asObject(entry);
            seatPoints.push_back(SeatPointsInput{asString(object.at("seat")), asInt(object.at("points"))});
        }
    }
    return WinRequest{
        stringArray(root, "handTiles"),
        std::move(melds),
        asString(root.at("winningTile")),
        asString(root.at("winType")),
        asString(root.at("winnerSeat")),
        optionalString(root, "discarderSeat"),
        optionalString(root, "seatWind"),
        optionalString(root, "roundWind"),
        std::move(seatPoints),
        stringArray(root, "flowerTiles"),
        stringArray(root, "flags")
    };
}

bool hasFlag(const std::vector<std::string>& flags, const char* target) {
    return std::find(flags.begin(), flags.end(), target) != flags.end();
}

char windToChar(const std::string& wind) {
    if (wind == "EAST") return 'E';
    if (wind == "SOUTH") return 'S';
    if (wind == "WEST") return 'W';
    if (wind == "NORTH") return 'N';
    throw std::runtime_error("Unsupported wind: " + wind);
}

std::string tileToHandToken(const std::string& tile) {
    if (tile.size() == 2 && std::isdigit(static_cast<unsigned char>(tile[1]))) {
        switch (tile[0]) {
            case 'W':
                return std::string{tile[1], 'm'};
            case 'T':
                return std::string{tile[1], 's'};
            case 'B':
                return std::string{tile[1], 'p'};
            case 'F':
                switch (tile[1]) {
                    case '1': return "E";
                    case '2': return "S";
                    case '3': return "W";
                    case '4': return "N";
                    default: break;
                }
            case 'J':
                switch (tile[1]) {
                    case '1':
                        return "P";
                    case '2':
                        return "F";
                    case '3':
                        return "C";
                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }
    if (tile.size() == 2 && (tile[1] == 'm' || tile[1] == 'p' || tile[1] == 's')) {
        return tile;
    }
    if (tile == "E" || tile == "S" || tile == "W" || tile == "N" || tile == "C" || tile == "F" || tile == "P") {
        return tile;
    }
    throw std::runtime_error("Unsupported tile code: " + tile);
}

std::string encodeNativeTile(const mahjong::Tile& tile) {
    if (tile.IsShu()) {
        char suit = tile.SuitChar();
        char prefix = suit == 'm' ? 'W' : suit == 's' ? 'T' : 'B';
        return std::string{prefix, tile.RankChar()};
    }
    switch (tile.TileChar()) {
        case 'E': return "F1";
        case 'S': return "F2";
        case 'W': return "F3";
        case 'N': return "F4";
        case 'P': return "J1";
        case 'F': return "J2";
        case 'C': return "J3";
        default: throw std::runtime_error("Unsupported native wait tile.");
    }
}

std::string compactTileList(const std::vector<std::string>& encodedTiles) {
    std::string result;
    std::string numberBuffer;
    char currentSuit = '\0';
    auto flushSuit = [&]() {
        if (!numberBuffer.empty()) {
            result += numberBuffer;
            result.push_back(currentSuit);
            numberBuffer.clear();
            currentSuit = '\0';
        }
    };
    for (const std::string& encoded : encodedTiles) {
        std::string token = tileToHandToken(encoded);
        if (token.size() == 2 && std::isdigit(static_cast<unsigned char>(token[0]))) {
            if (currentSuit != '\0' && currentSuit != token[1]) {
                flushSuit();
            }
            currentSuit = token[1];
            numberBuffer.push_back(token[0]);
            continue;
        }
        flushSuit();
        result += token;
    }
    flushSuit();
    return result;
}

int relationOffer(const std::optional<std::string>& relation, bool addedKong) {
    if (!relation.has_value()) {
        return addedKong ? 5 : 1;
    }
    if (*relation == "LEFT") {
        return addedKong ? 5 : 1;
    }
    if (*relation == "ACROSS") {
        return addedKong ? 6 : 2;
    }
    if (*relation == "RIGHT") {
        return addedKong ? 7 : 3;
    }
    return addedKong ? 5 : 1;
}

std::string encodeMeld(const MeldInput& meld) {
    std::string payload = compactTileList(meld.tiles);
    if (meld.type == "CHOW") {
        if (!meld.claimedTile.has_value()) {
            throw std::runtime_error("CHOW meld is missing claimedTile.");
        }
        int offer = 1;
        for (std::size_t index = 0; index < meld.tiles.size(); ++index) {
            if (meld.tiles[index] == *meld.claimedTile) {
                offer = static_cast<int>(index) + 1;
                break;
            }
        }
        return "[" + payload + "," + std::to_string(offer) + "]";
    }
    if (meld.type == "PUNG") {
        return "[" + payload + "," + std::to_string(relationOffer(meld.fromSeat, false)) + "]";
    }
    if (meld.type == "OPEN_KONG") {
        return "[" + payload + "," + std::to_string(relationOffer(meld.fromSeat, false)) + "]";
    }
    if (meld.type == "ADDED_KONG") {
        return "[" + payload + "," + std::to_string(relationOffer(meld.fromSeat, true)) + "]";
    }
    if (meld.type == "CONCEALED_KONG") {
        return "[" + payload + "]";
    }
    if (meld.type == "KONG") {
        return "[" + payload + (meld.open ? "," + std::to_string(relationOffer(meld.fromSeat, false)) : std::string()) + "]";
    }
    throw std::runtime_error("Unsupported meld type: " + meld.type);
}

std::string encodeFlowers(const std::vector<std::string>& flowerTiles) {
    std::string result;
    for (const std::string& tile : flowerTiles) {
        if (tile.size() == 1 && tile[0] >= 'a' && tile[0] <= 'h') {
            result += tile;
        }
    }
    return result;
}

template <typename Request>
std::string buildHandString(
    const Request& request,
    const std::optional<std::string>& winningTile,
    const std::string& winType
) {
    std::string value;
    for (const MeldInput& meld : request.melds) {
        value += encodeMeld(meld);
    }
    value += compactTileList(request.handTiles);
    if (winningTile.has_value()) {
        value += compactTileList({*winningTile});
    }
    char roundWind = windToChar(request.roundWind.value_or("EAST"));
    char seatWind = windToChar(request.seatWind.value_or("EAST"));
    value.push_back('|');
    value.push_back(roundWind);
    value.push_back(seatWind);
    value.push_back(winType == "SELF_DRAW" ? '1' : '0');
    value.push_back(hasFlag(request.flags, "LAST_OF_KIND") ? '1' : '0');
    value.push_back(hasFlag(request.flags, "LAST_TILE") ? '1' : '0');
    value.push_back((hasFlag(request.flags, "AFTER_KONG") || hasFlag(request.flags, "ROBBING_KONG")) ? '1' : '0');
    value.push_back('|');
    value += encodeFlowers(request.flowerTiles);
    return value;
}

mahjong::Handtiles parseHandtiles(const std::string& handString) {
    mahjong::Handtiles handtiles;
    int result = handtiles.StringToHandtiles(handString);
    if (result != 0) {
        throw std::runtime_error("GB-Mahjong rejected hand string: " + handString + " (code " + std::to_string(result) + ")");
    }
    return handtiles;
}

const char* fanKey(int fan) {
    static const char* KEYS[] = {
        "INVALID",
        "DASIXI",
        "DASANYUAN",
        "LVYISE",
        "JIULIANBAODENG",
        "SIGANG",
        "LIANQIDUI",
        "SHISANYAO",
        "QINGYAOJIU",
        "XIAOSIXI",
        "XIAOSANYUAN",
        "ZIYISE",
        "SIANKE",
        "YISESHUANGLONGHUI",
        "YISESITONGSHUN",
        "YISESIJIEGAO",
        "YISESIBUGAO",
        "SANGANG",
        "HUNYAOJIU",
        "QIDUI",
        "QIXINGBUKAO",
        "QUANSHUANGKE",
        "QINGYISE",
        "YISESANTONGSHUN",
        "YISESANJIEGAO",
        "QUANDA",
        "QUANZHONG",
        "QUANXIAO",
        "QINGLONG",
        "SANSESHUANGLONGHUI",
        "YISESANBUGAO",
        "QUANDAIWU",
        "SANTONGKE",
        "SANANKE",
        "QUANBUKAO",
        "ZUHELONG",
        "DAYUWU",
        "XIAOYUWU",
        "SANFENGKE",
        "HUALONG",
        "TUIBUDAO",
        "SANSESANTONGSHUN",
        "SANSESANJIEGAO",
        "WUFANHU",
        "MIAOSHOUHUICHUN",
        "HAIDILAOYUE",
        "GANGSHANGKAIHUA",
        "QIANGGANGHU",
        "PENGPENGHU",
        "HUNYISE",
        "SANSESANBUGAO",
        "WUMENQI",
        "QUANQIUREN",
        "SHUANGANGANG",
        "SHUANGJIANKE",
        "QUANDAIYAO",
        "BUQIUREN",
        "SHUANGMINGGANG",
        "HUJUEZHANG",
        "JIANKE",
        "QUANFENGKE",
        "MENFENGKE",
        "MENQIANQING",
        "PINGHU",
        "SIGUIYI",
        "SHUANGTONGKE",
        "SHUANGANKE",
        "ANGANG",
        "DUANYAO",
        "YIBANGAO",
        "XIXIANGFENG",
        "LIANLIU",
        "LAOSHAOFU",
        "YAOJIUKE",
        "MINGGANG",
        "QUEYIMEN",
        "WUZI",
        "BIANZHANG",
        "KANZHANG",
        "DANDIAOJIANG",
        "ZIMO",
        "HUAPAI",
        "MINGANGANG"
    };
    if (fan < 0 || fan >= static_cast<int>(sizeof(KEYS) / sizeof(KEYS[0]))) {
        return "UNKNOWN";
    }
    return KEYS[fan];
}

std::vector<FanEntry> collectFans(mahjong::Fan& fan) {
    std::vector<FanEntry> entries;
    for (int current = 1; current < mahjong::FAN_SIZE; ++current) {
        int count = static_cast<int>(fan.fan_table_res[current].size());
        if (count <= 0) {
            continue;
        }
        entries.push_back(FanEntry{fanKey(current), mahjong::FAN_SCORE[current], count});
    }
    return entries;
}

std::string encodeFans(const std::vector<FanEntry>& fans) {
    std::ostringstream output;
    output << "[";
    for (std::size_t index = 0; index < fans.size(); ++index) {
        if (index > 0) {
            output << ",";
        }
        output << "{"
               << "\"name\":" << jsonString(fans[index].name) << ","
               << "\"fan\":" << fans[index].fan << ","
               << "\"count\":" << fans[index].count
               << "}";
    }
    output << "]";
    return output.str();
}

std::string encodeScoreDeltas(const std::vector<ScoreDelta>& deltas) {
    std::ostringstream output;
    output << "[";
    for (std::size_t index = 0; index < deltas.size(); ++index) {
        if (index > 0) {
            output << ",";
        }
        output << "{"
               << "\"seat\":" << jsonString(deltas[index].seat) << ","
               << "\"delta\":" << deltas[index].delta
               << "}";
    }
    output << "]";
    return output.str();
}

std::string invalidFanResponse(const std::string& error) {
    return "{\"valid\":false,\"totalFan\":0,\"fans\":[],\"error\":" + jsonString(error) + "}";
}

std::string invalidTingResponse(const std::string& error) {
    return "{\"valid\":false,\"waits\":[],\"error\":" + jsonString(error) + "}";
}

std::string invalidWinResponse(const std::string& error) {
    return "{\"valid\":false,\"title\":\"WIN\",\"totalFan\":0,\"fans\":[],\"scoreDeltas\":[],\"error\":" + jsonString(error) + "}";
}

int nonFlowerFan(const mahjong::Fan& fan) {
    const int flowerFan = static_cast<int>(fan.fan_table_res[mahjong::FAN_HUAPAI].size())
        * mahjong::FAN_SCORE[mahjong::FAN_HUAPAI];
    return fan.tot_fan_res - flowerFan;
}

std::string evaluateFanJson(const FanRequest& request) {
    try {
        std::string handString = buildHandString(request, request.winningTile, request.winType);
        mahjong::Handtiles handtiles = parseHandtiles(handString);
        mahjong::Fan fan;
        if (!fan.JudgeHu(handtiles)) {
            return invalidFanResponse("Hand is not a valid GB Mahjong winning hand.");
        }
        fan.CountFan(handtiles);
        std::vector<FanEntry> fans = collectFans(fan);
        std::ostringstream output;
        output << "{"
               << "\"valid\":true,"
               << "\"totalFan\":" << fan.tot_fan_res << ","
               << "\"fans\":" << encodeFans(fans)
               << "}";
        return output.str();
    } catch (const std::exception& exception) {
        return invalidFanResponse(exception.what());
    }
}

std::string evaluateTingJson(const TingRequest& request) {
    try {
        std::string handString = buildHandString(request, std::nullopt, "");
        mahjong::Handtiles handtiles = parseHandtiles(handString);
        mahjong::Fan fan;
        std::vector<mahjong::Tile> waits = fan.CalcTing(handtiles);
        std::ostringstream output;
        output << "{\"valid\":true,\"waits\":[";
        bool firstWait = true;
        for (const mahjong::Tile& wait : waits) {
            mahjong::Handtiles candidate = handtiles;
            candidate.SetTile(wait);
            mahjong::Fan candidateFan;
            if (!candidateFan.JudgeHu(candidate)) {
                continue;
            }
            candidateFan.CountFan(candidate);

            // Preserve the historical discard-win fan result whenever it is
            // already legal. If it falls short, also evaluate the same wait
            // as a self draw: an open hand with seven fan may legally win by
            // adding the one-fan ZIMO pattern even though it cannot ron.
            mahjong::Fan selfDrawFan;
            mahjong::Fan* displayedFan = &candidateFan;
            if (nonFlowerFan(candidateFan) < 8) {
                mahjong::Handtiles selfDrawCandidate = handtiles;
                selfDrawCandidate.DrawTile(wait);
                selfDrawCandidate.SetZimo(1);
                if (!selfDrawFan.JudgeHu(selfDrawCandidate)) {
                    continue;
                }
                selfDrawFan.CountFan(selfDrawCandidate);
                if (nonFlowerFan(selfDrawFan) < 8) {
                    continue;
                }
                displayedFan = &selfDrawFan;
            }
            if (!firstWait) {
                output << ",";
            }
            firstWait = false;
            output << "{"
                   << "\"tile\":" << jsonString(encodeNativeTile(wait)) << ","
                   << "\"totalFan\":" << displayedFan->tot_fan_res << ","
                   << "\"fans\":" << encodeFans(collectFans(*displayedFan))
                   << "}";
        }
        output << "]}";
        return output.str();
    } catch (const std::exception& exception) {
        return invalidTingResponse(exception.what());
    }
}

std::vector<ScoreDelta> buildScoreDeltas(const WinRequest& request, int totalFan) {
    constexpr int BASIC_SCORE = 8;
    std::vector<ScoreDelta> deltas;
    if (request.winType == "SELF_DRAW") {
        const int payment = BASIC_SCORE + totalFan;
        int loserCount = 0;
        for (const SeatPointsInput& seat : request.seatPoints) {
            if (seat.seat == request.winnerSeat) {
                continue;
            }
            deltas.push_back(ScoreDelta{seat.seat, -payment});
            ++loserCount;
        }
        deltas.push_back(ScoreDelta{request.winnerSeat, payment * loserCount});
        return deltas;
    }
    if (!request.discarderSeat.has_value()) {
        return deltas;
    }
    int winnerDelta = 0;
    for (const SeatPointsInput& seat : request.seatPoints) {
        if (seat.seat == request.winnerSeat) {
            continue;
        }
        const int payment = BASIC_SCORE + (seat.seat == *request.discarderSeat ? totalFan : 0);
        deltas.push_back(ScoreDelta{seat.seat, -payment});
        winnerDelta += payment;
    }
    deltas.push_back(ScoreDelta{request.winnerSeat, winnerDelta});
    return deltas;
}

std::string evaluateWinJson(const WinRequest& request) {
    try {
        FanRequest fanRequest{
            request.handTiles,
            request.melds,
            request.winningTile,
            request.winType,
            request.seatWind,
            request.roundWind,
            request.flowerTiles,
            request.flags
        };
        std::string fanPayload = evaluateFanJson(fanRequest);
        JsonObject root = asObject(JsonParser(fanPayload).parse());
        bool valid = root.contains("valid") && asBool(root.at("valid"));
        if (!valid) {
            return invalidWinResponse(root.contains("error") ? asString(root.at("error")) : "GB Mahjong win evaluation failed.");
        }
        int totalFan = asInt(root.at("totalFan"));
        std::vector<FanEntry> fans;
        for (const JsonValue& entry : asArray(root.at("fans"))) {
            const JsonObject& object = asObject(entry);
            fans.push_back(FanEntry{
                asString(object.at("name")),
                asInt(object.at("fan")),
                asInt(object.at("count"))
            });
        }
        int flowerFan = 0;
        for (const FanEntry& fan : fans) {
            if (fan.name == "HUAPAI") {
                flowerFan += fan.fan * fan.count;
            }
        }
        if (totalFan - flowerFan < 8) {
            return invalidWinResponse("GB Mahjong winning hand must have at least 8 non-flower fan.");
        }
        std::vector<ScoreDelta> deltas = buildScoreDeltas(request, totalFan);
        std::ostringstream output;
        output << "{"
               << "\"valid\":true,"
               << "\"title\":" << jsonString(request.winType == "SELF_DRAW" ? "TSUMO" : "RON") << ","
               << "\"totalFan\":" << totalFan << ","
               << "\"fans\":" << encodeFans(fans) << ","
               << "\"scoreDeltas\":" << encodeScoreDeltas(deltas)
               << "}";
        return output.str();
    } catch (const std::exception& exception) {
        return invalidWinResponse(exception.what());
    }
}

jbyteArray newByteArray(JNIEnv* env, const std::string& value) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(value.size()));
    if (array != nullptr && !value.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<const jbyte*>(value.data()));
    }
    return array;
}

std::string fromJByteArray(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) {
        return "";
    }
    jsize length = env->GetArrayLength(array);
    if (length == 0) {
        return "";
    }
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    if (bytes == nullptr) {
        return "";
    }
    std::string result(reinterpret_cast<const char*>(bytes), static_cast<std::size_t>(length));
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_top_ellan_mahjong_gb_jni_GbMahjongNativeBridge_nativeLibraryVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("mahjongpaper-gb-jni/0.2.0");
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_ellan_mahjong_gb_jni_GbMahjongNativeBridge_nativePing(JNIEnv* env, jclass) {
    return env->NewStringUTF("mahjongpaper-gb-native-ready");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_ellan_mahjong_gb_jni_GbMahjongNativeBridge_nativeEvaluateFan(JNIEnv* env, jclass, jbyteArray requestJson) {
    try {
        return newByteArray(env, evaluateFanJson(parseFanRequest(fromJByteArray(env, requestJson))));
    } catch (const std::exception& exception) {
        return newByteArray(env, invalidFanResponse(exception.what()));
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_ellan_mahjong_gb_jni_GbMahjongNativeBridge_nativeEvaluateTing(JNIEnv* env, jclass, jbyteArray requestJson) {
    try {
        return newByteArray(env, evaluateTingJson(parseTingRequest(fromJByteArray(env, requestJson))));
    } catch (const std::exception& exception) {
        return newByteArray(env, invalidTingResponse(exception.what()));
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_ellan_mahjong_gb_jni_GbMahjongNativeBridge_nativeEvaluateWin(JNIEnv* env, jclass, jbyteArray requestJson) {
    try {
        return newByteArray(env, evaluateWinJson(parseWinRequest(fromJByteArray(env, requestJson))));
    } catch (const std::exception& exception) {
        return newByteArray(env, invalidWinResponse(exception.what()));
    }
}
