# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Everyday bKash users in Bangladesh: people paying at a shop counter, sending money to family, cashing out at an agent, checking their balance. Many are on inexpensive Android phones; some are new to smartphones. They use Chalaiden standing up, one-handed, often with a shopkeeper waiting.

## Product Purpose

Chalaiden is a front end for bKash's `*247#` USSD menu. It turns the menu's typed replies into a few taps, so a payment works without mobile data or the bKash app. The user picks Pay, Send, Cash out or Balance, gives the number and amount (Pay scans the number printed under a shop's bKash QR), and Chalaiden walks `*247#` for them. Success: the payment goes through quickly, and the user always types their own PIN.

## Positioning

Offline bKash, by USSD, with the menu-walking done for you. The name is a Bangladeshi meme: "চালাই দেন" (chalai den), "just run it": the bravado of pushing it through without overthinking. Chalaiden is the friend who says it for you.

## Operating Context

- Used at shop counters and agent points, outdoors and indoors, often in a hurry.
- Three ways to answer bKash: Guided mode (Chalaiden's keyboard types each reply, a floating card shows the step; any phone), Shizuku, and Root (automatic). Setup lists them in that order.
- bKash's own USSD dialog appears over the app during a session.

## Capabilities and Constraints

- Actions: Pay (scan or type the merchant number), Send, Cash out, Balance.
- No history and no payee list, by design.
- The PIN is only ever typed by the user; Chalaiden never stores, reads back or fills it.
- Must never help hide accessibility services, root or the app from bKash's fraud detection.
- Kotlin + Jetpack Compose; light and dark themes.
- Test number for demos and screenshots: 01302277488. Real people's numbers stay out of screenshots.

## Brand Commitments

- Name: Chalaiden, from the "চালাই দেন" meme. It is a fun, meme-driven app, and the home screen may carry that personality.
- Copy: English UI labels, with romanized Bangla (Banglish) for the meme lines.
- The four actions (Pay, Send, Cash out, Balance) stay large and in thumb reach at the bottom of Home.

## Evidence on Hand

- Logo mark and wordmark in `app/src/main/res/drawable/logo_mark.xml`; operator logos in `res/drawable-nodpi/sim_*.png`; Shizuku and root-manager icons in `res/drawable-nodpi/`.
- No testimonials, user counts or press exist; none may be invented.

## Product Principles

1. The payment comes first: fun never slows a payment or hides what is about to happen.
2. Confidence over caution: say "run it", and show exactly what will run.
3. The user's PIN is the user's alone.
4. Works on any phone, offline, for anyone.

## Accessibility & Inclusion

Large touch targets for one-handed use at a counter; legible at large font scales; both themes; plain words for first-time smartphone users.
