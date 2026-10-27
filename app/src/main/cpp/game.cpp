#include "game.hpp"
#include "il2cpp.hpp"
#include <android/log.h>
#include <array>
#include <limits>
#include <stdexcept>

namespace game {
using namespace il2cpp;
std::atomic<uint32_t> features{0};
std::atomic<bool> ready{false};
namespace {
bool enabled(Feature feature) {
    return ready.load(std::memory_order_acquire) && (features.load(std::memory_order_relaxed) & feature);
}
Class* gameClass(const char* name) { return klass("Assembly-CSharp.dll", "", name); }

using Judge = bool(Object*, const Method*);
struct NoteBinding {
    Call<Judge> judge;
    Judge* original{};
    Field note, progress, isJudged;
    void bind(const char* name) {
        auto klass = gameClass(name);
        judge.bind(klass, "Judge", "System.Boolean", {});
        note = field(klass, "noteInfor", "ChartNote");
        progress = field(klass, "progressControl", "ProgressControl");
        isJudged = field(klass, "isJudged", "System.Boolean");
    }
};
NoteBinding click, drag, flick, hold;
Field noteTime, progressTime, holdJudged, holdMissed, holdOver, holdSafeFrame;

// The real Judge method keeps note removal, SFX, hit FX, ghost-note bookkeeping
// and score events. Only the input timing/state is supplied by AutoPlay.
bool judgeNote(NoteBinding& binding, Object* self, const Method* method) {
    if (!enabled(AutoPlay)) return binding.original(self, method);
    auto note = binding.note.get<Object*>(self);
    auto progress = binding.progress.get<Object*>(self);
    const float time = noteTime.get<float>(note);
    const float now = progressTime.get<float>(progress);
    if (now < time) return false;
    binding.isJudged.set(self, true);
    progressTime.set(progress, time);
    const bool done = binding.original(self, method);
    progressTime.set(progress, now);
    return done;
}
bool clickJudge(Object* self, const Method* method) { return judgeNote(click, self, method); }
bool dragJudge(Object* self, const Method* method) { return judgeNote(drag, self, method); }
bool flickJudge(Object* self, const Method* method) { return judgeNote(flick, self, method); }
bool holdJudge(Object* self, const Method* method) {
    if (!enabled(AutoPlay) || holdMissed.get<bool>(self) || holdOver.get<bool>(self))
        return hold.original(self, method);
    const auto note = hold.note.get<Object*>(self);
    const auto progress = hold.progress.get<Object*>(self);
    const float time = noteTime.get<float>(note);
    const float now = progressTime.get<float>(progress);
    if (now < time) return false;
    // Refresh the game's existing per-frame release grace. The original method
    // performs head Perfect, sustained visuals, tail scoring and cleanup.
    holdSafeFrame.set(self, 2);
    if (holdJudged.get<bool>(self)) return hold.original(self, method);
    hold.isJudged.set(self, true);
    progressTime.set(progress, time);
    const bool done = hold.original(self, method);
    progressTime.set(progress, now);
    return done;
}

Call<void(Object*, const Method*)> update, stopAudio;
Call<void(Object*, const Method*)> levelUpdate;
void (*originalLevelUpdate)(Object*, const Method*);
Call<Object*(const Method*)> mainInformation;
struct Color { float r, g, b, a; };
Call<Color(Object*, const Method*)> getMaskColor;
Call<void(Object*, Color, const Method*)> setMaskColor;
Call<Object*(Object*, const Method*)> getMaskTexture;
Call<void(Object*, Object*, const Method*)> setMaskTexture;
Call<Object*(Object*, Object*, const Method*)> getComponent;
Class* progressClass;
Field sceneSwitchMask, chartLoaded, judgeLineColorSetOver, inChallengeMode;
bool challengeMaskHeld = false;
Call<void(Object*, const Method*)> challengeAwake;
void (*originalChallengeAwake)(Object*, const Method*);
void beginChallengePresentation(Object* self, const Method* method) {
    originalChallengeAwake(self, method);
    // The next challenge card owns presentation from here, including the
    // final visit that routes to the three-song results. Release our image
    // at this scene boundary, not during the outgoing gameplay frame.
    if (challengeMaskHeld) {
        auto mask = sceneSwitchMask.get<Object*>(mainInformation());
        setMaskColor(mask, Color{0, 0, 0, 0});
        setMaskTexture(mask, nullptr);
        challengeMaskHeld = false;
    }
}
void updateLevelPresentation(Object* self, const Method* method) {
    // Intercept only the gameplay reveal. Later frames belong to the native
    // scene transition, including the challenge's additive scene lifecycle.
    if (!enabled(FastPass) || !chartLoaded.get<bool>(self) || judgeLineColorSetOver.get<bool>(self)) {
        originalLevelUpdate(self, method);
        return;
    }
    // The challenge's incoming screenshot lasts through this skipped level;
    // the next challenge card releases it in beginChallengePresentation.
    auto progress = getComponent(self, typeObject(progressClass));
    challengeMaskHeld = inChallengeMode.get<bool>(progress);
    auto mask = sceneSwitchMask.get<Object*>(mainInformation());
    auto texture = getMaskTexture(mask);
    const auto color = getMaskColor(mask);
    originalLevelUpdate(self, method);
    // LevelInformation clears BOTH the texture and opacity of the persistent
    // transition mask. Keep the incoming image through the end-of-frame
    // capture; an opaque RawImage with no texture would render solid white.
    setMaskTexture(mask, texture);
    setMaskColor(mask, color);
}
void (*originalUpdate)(Object*, const Method*);
Field progressLevel, progressAudio, progressOverTime, progressPlaying, levelOver, audioLoaded;
void progressUpdate(Object* self, const Method* method) {
    if (enabled(FastPass)) {
        auto level = progressLevel.get<Object*>(self);
        auto audio = progressAudio.get<Object*>(self);
        if (chartLoaded.get<bool>(level) && audioLoaded.get<bool>(audio) && !levelOver.get<bool>(level)) {
            // Completion precedes the normal audio-start deadline. End the
            // playing state so Update cannot schedule a released audio clip.
            progressPlaying.set(self, false);
            stopAudio(audio);
            levelOver.set(level, true);
            // Expire the game's end timer before this frame's Update. The
            // original method consumes it once and sets its own finished state.
            // No duplicated 1.2/3.5-second constants or separate challenge path.
            progressOverTime.set(self, std::numeric_limits<float>::infinity());
            __android_log_print(ANDROID_LOG_INFO, "Phi-Native", "Fast Pass: resources ready -> finish");
        }
    }
    originalUpdate(self, method);
}

Call<Object*(Object*, const Method*)> result;
Object* (*originalResult)(Object*, const Method*);
Call<void(Object*, float, const Method*)> setScore, setPercent;
Field scoreLevel, noteCount, resultPerfect, resultCombo;
std::array<Field, 5> resultFailures;
Object* levelResult(Object* self, const Method* method) {
    auto info = originalResult(self, method);
    if (!enabled(FastPass)) return info;
    const int notes = noteCount.get<int>(scoreLevel.get<Object*>(self));
    setScore(info, 1000000.0f);
    setPercent(info, 100.0f);
    resultPerfect.set(info, notes);
    resultCombo.set(info, notes);
    for (const auto& field : resultFailures) field.set(info, 0);
    __android_log_print(ANDROID_LOG_INFO, "Phi-Native", "Fast Pass result: %d Perfect", notes);
    return info;
}

Call<bool(Object*, Object*, const Method*)> spend;
bool (*originalSpend)(Object*, Object*, const Method*);
bool spendMoney(Object* self, Object* money, const Method* method) {
    return enabled(FreePurchase) || originalSpend(self, money, method);
}
using ItemConstructor = void(Object*, Object*, int, Object*, Object*, const Method*);
Call<ItemConstructor> itemConstructor;
ItemConstructor* originalItemConstructor;
Call<Object*(int, const Method*)> moneyForMB;
Field itemPrice;
void constructItem(Object* self, Object* song, int unlockType, Object* unlockInfo,
                   Object* chartUnlockInfo, const Method* method) {
    originalItemConstructor(self, song, unlockType, unlockInfo, chartUnlockInfo, method);
    if (enabled(FreePurchase) && itemPrice.get<Object*>(self))
        itemPrice.setObject(self, moneyForMB(0));
}

template<class Signature> void install(HookFunction hook, const char* name,
        const Call<Signature>& method, Signature* replacement, Signature*& original) {
    const int error = hook(reinterpret_cast<void*>(method.function),
                           reinterpret_cast<void*>(replacement), reinterpret_cast<void**>(&original));
    if (error != 0) throw std::runtime_error(std::string("Hook failed: ") + name + " (" + std::to_string(error) + ")");
    __android_log_print(ANDROID_LOG_INFO, "Phi-Native", "bound %s @ %p", name, method.function);
}
}

void bind(HookFunction hook) {
    click.bind("ClickControl"); drag.bind("DragControl");
    flick.bind("FlickControl"); hold.bind("HoldControl");
    noteTime = field(gameClass("ChartNote"), "realTime", "System.Single");
    auto holdClass = gameClass("HoldControl");
    holdJudged = field(holdClass, "judged", "System.Boolean");
    holdMissed = field(holdClass, "missed", "System.Boolean");
    holdOver = field(holdClass, "judgeOver", "System.Boolean");
    holdSafeFrame = field(holdClass, "_safeFrame", "System.Int32");
    auto progress = gameClass("ProgressControl");
    progressClass = progress;
    getComponent.bind(klass("UnityEngine.CoreModule.dll", "UnityEngine", "Component"),
        "GetComponent", "UnityEngine.Component", {"System.Type"});
    inChallengeMode = field(progress, "_inChallengeMode", "System.Boolean");
    progressTime = field(progress, "nowTime", "System.Single");
    progressLevel = field(progress, "levelInformation", "LevelInformation");
    progressAudio = field(progress, "audioSource", "AddressableAudioSource");
    progressOverTime = field(progress, "_overTime", "System.Single");
    progressPlaying = field(progress, "isPlaying", "System.Boolean");
    audioLoaded = field(gameClass("AddressableAudioSource"), "loadOver", "System.Boolean");
    update.bind(progress, "Update", "System.Void", {});
    stopAudio.bind(gameClass("AddressableAudioSource"), "Stop", "System.Void", {});
    auto level = gameClass("LevelInformation");
    levelUpdate.bind(level, "Update", "System.Void", {});
    challengeAwake.bind(klass("Assembly-CSharp.dll", "Phigros2.ChallengeMode.Scripts", "ChallengeModeControl"),
        "Awake", "System.Void", {});
    auto information = gameClass("GameInformation");
    mainInformation.bind(information, "get_main", "GameInformation", {}, true);
    sceneSwitchMask = field(information, "sceneSwitchMask", "UnityEngine.UI.RawImage");
    getMaskColor.bind(klass("UnityEngine.UI.dll", "UnityEngine.UI", "Graphic"),
        "get_color", "UnityEngine.Color", {});
    setMaskColor.bind(klass("UnityEngine.UI.dll", "UnityEngine.UI", "Graphic"),
        "set_color", "System.Void", {"UnityEngine.Color"});
    auto rawImage = klass("UnityEngine.UI.dll", "UnityEngine.UI", "RawImage");
    getMaskTexture.bind(rawImage, "get_texture", "UnityEngine.Texture", {});
    setMaskTexture.bind(rawImage, "set_texture", "System.Void", {"UnityEngine.Texture"});
    chartLoaded = field(level, "chartLoaded", "System.Boolean");
    judgeLineColorSetOver = field(level, "judgeLineColorSetOver", "System.Boolean");
    levelOver = field(level, "levelOver", "System.Boolean");
    noteCount = field(level, "numOfNotes", "System.Int32");
    auto score = gameClass("ScoreControl");
    scoreLevel = field(score, "levelInformation", "LevelInformation");
    result.bind(score, "GetLevelResultInfo", "LevelResultInfo", {});
    auto resultClass = gameClass("LevelResultInfo");
    setScore.bind(resultClass, "set_Score", "System.Void", {"System.Single"});
    setPercent.bind(resultClass, "set_Percent", "System.Void", {"System.Single"});
    resultPerfect = field(resultClass, "perfect", "System.Int32");
    resultCombo = field(resultClass, "maxCombo", "System.Int32");
    const char* failureNames[] = {"good", "bad", "miss", "early", "late"};
    for (size_t i = 0; i < resultFailures.size(); ++i)
        resultFailures[i] = field(resultClass, failureNames[i], "System.Int32");
    spend.bind(gameClass("MoneyControl"), "SpendMoney", "System.Boolean", {"Money"});
    auto item = gameClass("SongSelectorItem");
    itemConstructor.bind(item, ".ctor", "System.Void",
        {"SongsItem", "System.Int32", "System.String", "System.Boolean[]"});
    itemPrice = field(item, "price", "Money");
    moneyForMB.bind(gameClass("Money"), "ForMB", "Money", {"System.Int32"}, true);

    // Bind the complete contract before modifying any method entry point.
    install(hook, "ClickControl.Judge", click.judge, clickJudge, click.original);
    install(hook, "DragControl.Judge", drag.judge, dragJudge, drag.original);
    install(hook, "FlickControl.Judge", flick.judge, flickJudge, flick.original);
    install(hook, "HoldControl.Judge", hold.judge, holdJudge, hold.original);
    install(hook, "ProgressControl.Update", update, progressUpdate, originalUpdate);
    install(hook, "LevelInformation.Update", levelUpdate, updateLevelPresentation, originalLevelUpdate);
    install(hook, "ChallengeModeControl.Awake", challengeAwake, beginChallengePresentation, originalChallengeAwake);
    install(hook, "ScoreControl.GetLevelResultInfo", result, levelResult, originalResult);
    install(hook, "MoneyControl.SpendMoney", spend, spendMoney, originalSpend);
    install(hook, "SongSelectorItem..ctor", itemConstructor, constructItem, originalItemConstructor);
    ready.store(true, std::memory_order_release);
}
} // namespace game
