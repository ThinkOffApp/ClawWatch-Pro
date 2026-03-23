import SwiftUI

struct ContentView: View {
    @ObservedObject var viewModel: ClawWatchViewModel
    @AppStorage(ClawWatchSettingKey.roomPostingEnabled) private var roomPostingEnabled = ClawWatchSettings.default.roomPostingEnabled
    @AppStorage(ClawWatchSettingKey.antFarmRoom) private var antFarmRoom = ClawWatchSettings.default.antFarmRoom
    @AppStorage(ClawWatchSettingKey.model) private var model = ClawWatchSettings.default.model

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    header
                    avatarStage
                    quickPrompts
                    composer

                    if roomPostingEnabled {
                        roomComposer
                    }

                    transcriptView
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
            .background(
                LinearGradient(
                    colors: [
                        Color(red: 0.06, green: 0.05, blue: 0.04),
                        Color(red: 0.15, green: 0.07, blue: 0.03)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            )
            .navigationTitle("ClawWatch")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.openSettings()
                    } label: {
                        Image(systemName: "gearshape.fill")
                    }
                }
            }
            .sheet(isPresented: $viewModel.isSettingsPresented) {
                NavigationStack {
                    SettingsView()
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                chip(viewModel.state.statusLabel, color: chipColor)
                chip(antFarmRoom, color: .white.opacity(0.18))
            }

            Text(model)
                .font(.system(size: 11, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.68))
                .lineLimit(1)
        }
    }

    private var avatarStage: some View {
        ZStack(alignment: .top) {
            VStack(spacing: 8) {
                ClawAvatarView(state: viewModel.state, mood: viewModel.mood)
                Text("Orange lobster / Apple Watch")
                    .font(.system(size: 11, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.7))
            }
            if !viewModel.statusText.isEmpty {
                StatusBubbleView(text: viewModel.statusText, style: viewModel.bubbleStyle())
                    .offset(y: -8)
            }
        }
        .padding(.top, 18)
        .padding(.bottom, 8)
    }

    private var quickPrompts: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(WatchQuickPrompt.allCases) { prompt in
                    Button(prompt.rawValue) {
                        viewModel.useQuickPrompt(prompt)
                    }
                    .buttonStyle(.bordered)
                    .tint(.orange)
                }
            }
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Ask ClawWatch")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.9))
            TextField("Type or dictate…", text: $viewModel.prompt, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...5)
                .submitLabel(.send)
                .onSubmit {
                    viewModel.sendPrompt()
                }

            Button {
                viewModel.sendPrompt()
            } label: {
                Label("Ask", systemImage: "sparkles")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
        }
    }

    private var roomComposer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Post to room")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.9))
            TextField("Room message…", text: $viewModel.roomDraft, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...4)

            HStack(spacing: 8) {
                Button("Post") {
                    viewModel.postDraftToRoom()
                }
                .buttonStyle(.borderedProminent)
                .tint(.blue)

                Button("Post reply") {
                    viewModel.postLastReplyToRoom()
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var transcriptView: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let lastError = viewModel.lastError {
                Text(lastError)
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundStyle(.red.opacity(0.92))
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }

            ForEach(viewModel.transcript) { item in
                VStack(alignment: .leading, spacing: 3) {
                    Text(roleLabel(item.role))
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(roleColor(item.role))
                    Text(item.text)
                        .font(.system(size: 12, weight: .medium, design: .rounded))
                        .foregroundStyle(.white.opacity(0.94))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(roleBackground(item.role))
                )
            }
        }
    }

    private var chipColor: Color {
        switch viewModel.state {
        case .idle:
            return .white.opacity(0.18)
        case .listening:
            return .cyan.opacity(0.28)
        case .thinking, .searching:
            return .yellow.opacity(0.26)
        case .speaking:
            return .orange.opacity(0.28)
        case .error:
            return .red.opacity(0.28)
        }
    }

    private func chip(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold, design: .rounded))
            .foregroundStyle(.white.opacity(0.9))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color, in: Capsule())
    }

    private func roleLabel(_ role: ConversationRole) -> String {
        switch role {
        case .user:
            return "You"
        case .claw:
            return "ClawWatch"
        case .system:
            return "System"
        }
    }

    private func roleColor(_ role: ConversationRole) -> Color {
        switch role {
        case .user:
            return .orange.opacity(0.95)
        case .claw:
            return .mint.opacity(0.95)
        case .system:
            return .yellow.opacity(0.95)
        }
    }

    private func roleBackground(_ role: ConversationRole) -> Color {
        switch role {
        case .user:
            return Color.orange.opacity(0.16)
        case .claw:
            return Color.white.opacity(0.08)
        case .system:
            return Color.yellow.opacity(0.12)
        }
    }
}
