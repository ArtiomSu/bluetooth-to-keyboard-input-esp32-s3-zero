# Script Support

This supports the use of duckyscript like syntax for more complex behavior.

All lines must start with a command

### Basic Strings 

```text
REM This is a comment will be ingored by the parser
STRING Hello World
STRINGLN Hello World with a new line
```

The STRING commands support the use of special characters like `#` for example so you don't need to do shift+3 to get a `#` character. And they support capital letters without the need to do shift+letter. 

### Delay

To add a delay in milliseconds. This is basically a pause in the script. Has nothing to do with the delay settings of the device. This delay is performed client side so a delay of 1000 might results in a real world delay of 1100 milliseconds. This is not meant to be accurate, its more for wait a minimum of 1000 milliseconds for a terminal to open for example.

```text
DELAY 1000
```

### SPECIAL KEYS

Use these one per line to send special keys.

So you can do

```text
DELETE
DELETE
```

but not

```text
DELETE DELETE
```

`UPARROW` `DOWNARROW` `LEFTARROW` `RIGHTARROW`

`PAGEUP` `PAGEDOWN` `HOME` `END`

`INSERT` `DELETE` `DEL` `BACKSPACE`

`TAB` `SPACE`

`ENTER` `ESCAPE`

`INSERT` `PRINTSCREEN`

`CAPSLOCK` `NUMLOCK` `SCROLLLOCK`

`F1` `F2` `F3` `F4` `F5` `F6` `F7` `F8` `F9` `F10` `F11` `F12`

`MENU`

### MODIFIER KEYS

Modifier keys are slightly different as you must specify if you want to press or release the modifier key.

`SHIFT_DOWN` `SHIFT_UP` `ALT_DOWN` `ALT_UP` `CTRL_DOWN` `CTRL_UP` `GUI_DOWN` `GUI_UP` `ALTGR_DOWN` `ALTGR_UP`

`RELEASE_ALL` to release all modifier keys at once.

GUI is the windows key on windows and the command key on macOS.

So you can do

```text
SHIFT_DOWN
STRING Hello World
SHIFT_UP
```

If you have some cool keybindings setup you can do something like this to open a terminal

```text
SHIFT_DOWN
ALT_DOWN
GUI_DOWN
ENTER
GUI_UP
ALT_UP
SHIFT_UP
```

Or you can do

```text
SHIFT_DOWN
ALT_DOWN
GUI_DOWN
ENTER
RELEASE_ALL
```

### Repeat

`REPEAT n` re-executes the immediately preceding command `n` more times. Blank lines and comments (`REM`) are skipped when determining the previous command — `REPEAT` always refers to the last actual command that was executed.

`REPEAT` cannot be the first command in a script and cannot refer to another `REPEAT`.

```text
DELETE
REPEAT 4
```

This sends `DELETE` five times in total (once from the original line, then four more from `REPEAT`).

```text
SHIFT_DOWN
ALT_DOWN
GUI_DOWN
ENTER
RELEASE_ALL
DELAY 1000
REPEAT 2
```

Here `REPEAT 2` repeats `DELAY 1000` two more times, giving a total pause of 3 seconds.

### Modify device settings

You can modify the same settings as you can with the python tool.

`SET_LAYOUT en-US` to change the layout can also use `en-GB`.

`SET_OS macos` to change special character handling. Can also be `other`. `other` is default.

`SET_MIN_DELAY 20` to change the minimum delay between key presses in milliseconds.

`SET_MAX_DELAY 20` to change the maximum delay between key presses in milliseconds.

These settings can be changed at any time in the script and will affect subsequent lines. So for example you can start of typing slowly and then speed up the typing after a certain point in the script.

```text
SET_MIN_DELAY 100
SET_MAX_DELAY 200
STRING This will be typed slowly
SET_MIN_DELAY 5
SET_MAX_DELAY 20
STRING This will be typed quickly
```




