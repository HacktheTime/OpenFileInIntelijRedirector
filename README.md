This tool allows opening files in intellij similar to jetbrains toolbox open function but instead more useful.

the Toolbox link require a set path which is annoying if you have auto generated classes etc. So instead when opening the link it will search through your entire project by name and will check whether the package and name matches and will then proceed to open it via that jetbrains toolbox link.

scheme example:     http://localhost:9090/open?project=BBsentials-Server&class=de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100

you can also specify &line={line} at the end for optional line.

This tool requires wmcrtl(apt) and xdotool(apt) as well
as [Jetbrains Toolbox](https://www.jetbrains.com/de-de/lp/toolbox/). Due to that I am pretty sure it will only work on
linux. wmcrtl and xdotool are used for focusing the right window when opening since toolbox is focusing the incorrect one. due to the way this was done its quirky but seemed to work good enough.

Keep in mind that I hacked this together relatively quickly with low effort, but wanted to publish this as a small useful gadget. I will NOT maintain this, but merge pull requests that I see a Reason for.

If you want something etc make it yourself and make a pr. Make sure its generally useful and not specific for you only (
aka no project binding etc)