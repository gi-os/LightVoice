## LightVoice v1.2 — Setup tells the truth about push-to-talk

**The Settings screen said push-to-talk was off while it was working perfectly well.** That is
fixed. If you have been staring at a line reading OFF and wondering why your key still opened the
mic, nothing was wrong with the phone — the readout was.

The check compared the name of the accessibility service against the list Android keeps of which
services are switched on. Both are the same component written down, but Android has two ways of
writing it — the long form and a short one — and the app only knew the long one. So a service
that was running matched nothing and reported itself dead. It now compares the components rather
than the text, which accepts either form. Nothing about push-to-talk itself changed; only what
Settings says about it.

Under that, this release moves the shared plumbing out of the app. The wheel handling, the
shake-to-report gesture and the Akkurat type all now come from `light-common`, the same library
the other Light apps use, instead of being a copy that drifts. Two things you might notice from
that. A shake no longer throws a sheet over what you were reading — it puts a small chip in the
corner, and only tapping the chip opens the report form, so a misread shake costs you a glance
instead of an interruption. And an issue filed from the phone now carries the app version in its
title and a `crash` label when there was a stack trace, which is the difference between a report
that gets fixed and one that sits in a list.

LightVoice also joins LightSync. Your alarms, timers, reminders and notes now travel in the
nightly backup. They are the part of this app that exists nowhere else: it schedules its own
alarms because LightOS has no clock app to hand them to, so until now a wiped phone meant a
weekday alarm that simply never rang again, with nothing to notice until the morning. API keys
and settings ride along in the same file — cheap to retype, but not free. Recorded audio does
not: the last thing you said and the last thing it said back are cached for seconds and are not
worth sending anywhere.

The release build is now shrunk and obfuscated with R8 in full mode, which is where the risk in
this release sits. Full mode deletes anything it cannot see being used, and the parts of this app
nothing calls by name — the accessibility service, the alarm receiver, the boot receiver, the
ring service — are exactly the parts it would delete. They are named individually in the keep
rules, and the keep rules carry the reason each one is there. It has been reasoned through rather
than tested on hardware, so if push-to-talk stops responding or an alarm goes quiet after
updating, that is the thing to suspect, and the debug APK on the same release is built without
any of it.
