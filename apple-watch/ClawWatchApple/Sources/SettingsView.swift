import SwiftUI

struct SettingsView: View {
    @AppStorage(ClawWatchSettingKey.anthropicAPIKey) private var anthropicAPIKey = ""
    @AppStorage(ClawWatchSettingKey.antFarmAPIKey) private var antFarmAPIKey = ""
    @AppStorage(ClawWatchSettingKey.antFarmBaseURL) private var antFarmBaseURL = ClawWatchSettings.default.antFarmBaseURL
    @AppStorage(ClawWatchSettingKey.antFarmRoom) private var antFarmRoom = ClawWatchSettings.default.antFarmRoom
    @AppStorage(ClawWatchSettingKey.model) private var model = ClawWatchSettings.default.model
    @AppStorage(ClawWatchSettingKey.systemPrompt) private var systemPrompt = ClawWatchSettings.default.systemPrompt
    @AppStorage(ClawWatchSettingKey.roomPostingEnabled) private var roomPostingEnabled = ClawWatchSettings.default.roomPostingEnabled

    var body: some View {
        List {
            Section("Anthropic") {
                TextField("API key", text: $anthropicAPIKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Model", text: $model)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("System prompt", text: $systemPrompt, axis: .vertical)
                    .lineLimit(4...8)
            }

            Section("Room") {
                Toggle("Enable room posting", isOn: $roomPostingEnabled)
                TextField("Ant Farm URL", text: $antFarmBaseURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Room", text: $antFarmRoom)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("X-API-Key", text: $antFarmAPIKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
        }
        .navigationTitle("Settings")
    }
}
