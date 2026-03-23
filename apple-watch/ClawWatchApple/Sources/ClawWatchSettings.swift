import Foundation

enum ClawWatchSettingKey {
    static let anthropicAPIKey = "applewatch_anthropic_api_key"
    static let antFarmAPIKey = "applewatch_antfarm_api_key"
    static let antFarmBaseURL = "applewatch_antfarm_base_url"
    static let antFarmRoom = "applewatch_antfarm_room"
    static let model = "applewatch_model"
    static let systemPrompt = "applewatch_system_prompt"
    static let roomPostingEnabled = "applewatch_room_posting_enabled"
}

struct ClawWatchSettings {
    var anthropicAPIKey: String
    var antFarmAPIKey: String
    var antFarmBaseURL: String
    var antFarmRoom: String
    var model: String
    var systemPrompt: String
    var roomPostingEnabled: Bool

    static let defaultSystemPrompt =
        "You are ClawWatch, an intelligent and relaxed companion living on a watch. " +
        "Reply in 1 to 3 short spoken sentences. No markdown, no bullet points, no stiff assistant phrasing. " +
        "Sound natural, grounded, and warm. You can help with the wearer's body, their team rooms, timers, and live information. " +
        "Do not keep reminding people that you are a smartwatch unless they ask."

    static let `default` = ClawWatchSettings(
        anthropicAPIKey: "",
        antFarmAPIKey: "",
        antFarmBaseURL: "https://antfarm.world",
        antFarmRoom: "thinkoff-development",
        model: "claude-opus-4-6",
        systemPrompt: defaultSystemPrompt,
        roomPostingEnabled: true
    )

    static func load(from defaults: UserDefaults = .standard) -> ClawWatchSettings {
        var settings = ClawWatchSettings.default
        settings.anthropicAPIKey = defaults.string(forKey: ClawWatchSettingKey.anthropicAPIKey) ?? settings.anthropicAPIKey
        settings.antFarmAPIKey = defaults.string(forKey: ClawWatchSettingKey.antFarmAPIKey) ?? settings.antFarmAPIKey
        settings.antFarmBaseURL = defaults.string(forKey: ClawWatchSettingKey.antFarmBaseURL) ?? settings.antFarmBaseURL
        settings.antFarmRoom = defaults.string(forKey: ClawWatchSettingKey.antFarmRoom) ?? settings.antFarmRoom
        settings.model = defaults.string(forKey: ClawWatchSettingKey.model) ?? settings.model
        settings.systemPrompt = defaults.string(forKey: ClawWatchSettingKey.systemPrompt) ?? settings.systemPrompt
        if defaults.object(forKey: ClawWatchSettingKey.roomPostingEnabled) != nil {
            settings.roomPostingEnabled = defaults.bool(forKey: ClawWatchSettingKey.roomPostingEnabled)
        }
        return settings
    }
}
