# ClawWatch Companion App UI Plan

## Goal

Build the **ClawWatch phone companion app** entirely in `ClawWatch-Pro`, while reusing the best proven UI, billing, and backend-facing pieces from `ThinkOff`.

The public `ClawWatch` repo stays watch-only:
- no public phone companion app
- no public Play Store shell
- web admin only for the OS/watch-first path

The private companion app should feel like a focused **ClawWatch product**, not like a generic ThinkOff clone. The right approach is to **take the strongest existing ThinkOff components and trim them down** to the three ClawWatch views already defined in the product plan.

## Product Positioning

### Public OS version
- Watch-first core
- Local voice loop
- Web admin
- No phone app requirement

### Private Pro companion app
- Android app distributed through the Play Store
- ClawWatch onboarding, pairing, and management
- Default private Ant Farm room as the home experience
- Room browsing and family/agent coordination
- Billing, notifications, and account management

## Primary UX Principles

1. The app opens into **your ClawWatch room**, not a dashboard.
2. The chat experience should inherit ThinkOff's strongest refinements:
- smooth token streaming
- stable autoscroll behavior
- tuned composer/input sizing
- floating controls that do not fight the reading flow
3. The watch dashboard should feel secondary but powerful:
- easy to access
- not the default landing screen
4. The UI should feel like one coherent product:
- chat-first
- intimate
- a little premium
- lighter and calmer than current ThinkOff multi-model comparison UI

## App Information Architecture

### Root navigation

Use a **3-tab bottom navigation**:

1. `Room`
- default tab
- opens the user's private ClawWatch room

2. `Rooms`
- room list, join, invites, browse public rooms

3. `Watch`
- watch dashboard and admin

This keeps the product aligned with the scratchpad plan:
- Chat Room View
- My Rooms Menu
- ClawWatch Dashboard

## Screen Plan

### 1. Room Tab

#### Purpose
The main daily-use screen. This is where the user experiences ClawWatch as part of their AI family and team.

#### Structure
- room header
  - room title
  - room avatar/icon
  - presence / sync / notification status
  - quick jump to room settings
- message list
- composer
- floating quick actions
- optional right/top sheet for scratchpad and gather

#### Required features
- streaming room messages
- watch agent messages clearly distinguished
- human + bot messages in the same flow
- reply threading
- scratchpad access
- gather access
- quick room summary action

#### ClawWatch-specific quick actions
- `Check on watch`
- `Push settings`
- `Summarize family`
- `Open scratchpad`
- `Invite someone`

#### ThinkOff pieces to reuse
- `ChatInput.tsx`
  - autosizing composer
  - keyboard handling
  - input history navigation
  - attachment slotting pattern if images later matter
- `ChatMessage.tsx`
  - message card spacing
  - markdown rendering hooks
  - expandable action affordances
  - stable token-stream rendering approach
- scrolling and streaming patterns from:
  - `DirectChat.tsx`
  - `services/api.ts`
  - `services/db.ts`

#### Adaptation needed
- remove ThinkOff comparison/judging complexity
- replace provider-centric message visuals with:
  - human
  - watch agent
  - other agents
  - system/status
- optimize for room chronology and replies, not multi-model answer panels

### 2. Rooms Tab

#### Purpose
Room management and discovery without breaking the chat-first product.

#### Structure
- top search / filter row
- pinned rooms section
- joined rooms section
- invites / pending section
- public room discovery section
- FAB for `Create room`

#### Required features
- open joined rooms
- create room
- join by invite
- accept room invitations
- browse public rooms
- pin/favorite rooms

#### ClawWatch-specific behavior
- user's private ClawWatch room is always pinned at the top
- clear distinction between:
  - `My ClawWatch room`
  - other private rooms
  - public rooms

#### ThinkOff pieces to reuse
- general list/card visual treatment
- floating action button behavior
- panel and modal styling from current ThinkOff design system

#### New implementation needed
- actual Ant Farm room list UI
- invite and discovery flows
- room membership / role handling

### 3. Watch Tab

#### Purpose
Bring the current localhost admin power into a consumer-grade phone UI.

#### Sections

##### A. Watch Status
- connected / disconnected
- last sync time
- battery
- current avatar
- current model
- current room mode

##### B. Quick Controls
- push sync
- test message
- switch avatar
- change voice
- open logs

##### C. AI Settings
- model
- max tokens
- RAG mode
- system prompt

##### D. Room/Family Settings
- default family room
- allowed rooms
- notification mode
- summary mode

##### E. Diagnostics
- latest errors
- network/API state
- app version
- watch version

#### ThinkOff pieces to reuse
- settings card treatment from existing panels
- modal patterns
- form density / spacing / typography
- purchase / gated-feature presentation patterns

#### Existing ClawWatch admin concepts to port
- from `admin/index.html`
- from `admin/server.js`

#### Important rule
Do **not** port the localhost/ADB developer-admin UX directly as-is.

The companion app dashboard should become:
- account-linked
- phone-to-watch synced
- user-facing

not:
- shell-command-oriented
- restart the app manually
- push files over ADB from a laptop

## ThinkOff Reuse Map

### Reuse almost directly
- `thinkoff.app/src/components/ChatInput.tsx`
  - composer behavior
  - autosize
  - button layout logic
  - keyboard/history handling
- `thinkoff.app/src/components/DirectChat.tsx`
  - message list rhythm
  - scroll-to-bottom behavior
  - polling / refresh interaction ideas
- `thinkoff.app/src/components/PurchaseModal.tsx`
  - pricing modal structure
  - premium upgrade presentation
- `thinkoff.app/src/services/firebase.ts`
  - auth/session foundation
- `thinkoff.app/src/services/stripe.ts`
  - billing integration foundation

### Reuse with heavy adaptation
- `thinkoff.app/src/components/ChatMessage.tsx`
  - keep layout primitives
  - remove comparison-specific logic
  - replace with room-message and reply UI

### Do not carry over as primary UX
- truth-finding panel complexity
- multi-provider comparison affordances
- judge/result-specific controls
- research/compare-first framing

Those can become optional Pro modules later, but they should not dominate the ClawWatch companion app.

## Visual Direction

### Tone
- more personal than ThinkOff
- more intimate than Ant Farm desktop
- less “lab/evaluation”
- more “living agent on your wrist”

### UI direction
- dark-first premium UI is acceptable here because ThinkOff already has that visual language
- preserve the polished streaming/chat feel
- use ClawWatch avatar art prominently
- avoid generic settings-app flatness

### Key visual anchors
- room tab header should show the selected ClawWatch avatar
- watch tab should display live avatar + device state
- upgrade/paywall moments should feel like enabling your AI family, not buying credits for a tool

## Billing and Product Packaging

### Basic
- app access
- shared/public room participation
- maybe one default ClawWatch-linked room experience

### Pro
- private ClawWatch room
- AI family features
- watch dashboard and advanced settings
- custom room integrations
- deeper ThinkOff capabilities

### Source of truth
- billing and entitlement logic should reuse ThinkOff patterns where possible
- ClawWatch-specific entitlement checks should live in private repo code, not in the public OS repo

## Backend / API Plan

### Shared API surface
Same contracts should support:
- OS web admin
- Pro phone companion app

### Needed companion app backend flows
- auth/session
- entitlement/billing status
- room bootstrap for a new ClawWatch user
- room list / room membership / invites
- watch-device pairing state
- watch settings sync
- notification registration

### Practical implementation rule
The private app should consume the same watch settings schema the OS admin already uses, then extend it for:
- account-linked sync
- FCM / notifications
- family room bootstrap
- billing-aware features

## Implementation Sequence

### Phase 1: UI shell from ThinkOff
- create private Android phone UI shell in `ClawWatch-Pro`
- port/adapt:
  - chat input
  - message components
  - purchase modal
  - auth/billing/service wiring patterns

### Phase 2: Core companion flows
- sign in
- pair watch
- bootstrap private ClawWatch room
- open default room as home
- show rooms list
- show watch dashboard

### Phase 3: ClawWatch-specific polish
- avatar-first room header
- better room quick actions
- family summary surfaces
- notification settings
- diagnostics

### Phase 4: Deeper ThinkOff integration
- truth-finding / direct LLM tools as optional secondary features
- not the default home experience

## Repo Ownership Plan

### `ClawWatch`
- public watch OS app only
- no phone companion code
- web admin only

### `ClawWatch-Pro`
- private phone companion app
- private Play Store packaging
- private watch distribution/signing specifics
- adaptation of ThinkOff components into ClawWatch UX

### `ThinkOff`
- source of reusable chat, billing, auth, and backend integration patterns
- not the shipping home of the ClawWatch companion product

## Immediate Next Step

Start implementation in `ClawWatch-Pro` with:
1. app shell + bottom navigation
2. Room tab using adapted ThinkOff chat stack
3. Watch tab using a consumer version of the current admin features
4. Rooms tab with private-room-first UX

This is the shortest path to a Play Store-ready ClawWatch companion that feels polished from day one and does not duplicate the years of tuning already invested in ThinkOff's chat experience.
