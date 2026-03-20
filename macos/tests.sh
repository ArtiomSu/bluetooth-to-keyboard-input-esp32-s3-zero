#!/usr/bin/env bash

source .venv/bin/activate

MAIN_LAYOUT="en-GB"
MAIN_OS="macos"
DEVICE="home"

function generate_pattern() {
    length=$1
    pattern="1234567890abcdefghijklmnopqrstuvwxyz"
    result=""

    while [ ${#result} -lt $length ]; do
        result+="$pattern"
    done

    echo "${result:0:$length}"
}

function check_pattern() {
    input_data=$1
    expected_pattern=$2

    if [ "$input_data" == "$expected_pattern" ]; then
        echo ""
        echo "Test passed: Received input matches the expected pattern."
        echo ""
        echo ""
    else
        echo ""
        echo "Test failed: Received input does not match the expected pattern."
        echo "Received   input: $input_data"
        echo "Expected pattern: $expected_pattern"
        echo ""
        echo ""
        exit 1
    fi
}

function simple_test(){
    pattern="$1"
    layout="$2"
    os="$3"
    device="$4"
    echo "Generated pattern: $pattern"
    python send_ble.py --device "$device" --layout "$layout" --os "$os" --enter "$pattern"
    # read input from stdin
    IFS= read -r input_data
    check_pattern "$input_data" "$pattern"
}

function test_448(){
    # 447 + enter is the max chars we can send
    pattern_448=$(generate_pattern 447)
    simple_test "$pattern_448" "$MAIN_LAYOUT" "$MAIN_OS" "$DEVICE"
}

function test_896(){
    pattern_896=$(generate_pattern 895)
    simple_test "$pattern_896" "$MAIN_LAYOUT" "$MAIN_OS" "$DEVICE"
}

function test_full_uk_kb(){
    pattern="1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!@#\$%^&*()_+-=~\`[]{}|;:'\",.<>/?£€"
    simple_test "$pattern" "en-GB" "$MAIN_OS" "$DEVICE"
}

function test_full_us_kb(){
    pattern="1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!@#\$%^&*()_+-=~\`[]{}|;:'\",.<>/?"
    simple_test "$pattern" "en-US" "$MAIN_OS" "$DEVICE"
}

function long_char_test(){
    pattern="$1"
    echo "Generated pattern of length 1344: $pattern"

    echo "For this test you will need to open vim in a new terminal"
    echo "vim /tmp/t.text +startinsert"
    read -n 1 -s -r -p "Press any key to continue..."
    
    echo "Waiting for 5 seconds to allow you to open vim and get ready..."
    sleep 5

    python send_ble.py --device "$DEVICE" --layout en-GB --os macos "$pattern"
    
    echo "Save the file in vim and exit. You can do this by pressing Esc, then typing :wq and hitting Enter."
    read -n 1 -s -r -p "Press any key to continue..."
    
    input_data="$(cat /tmp/t.text)"
    
    check_pattern "$input_data" "$pattern"

    rm /tmp/t.text
}

function test_1344(){
    pattern_1344=$(generate_pattern 1343)
    long_char_test "$pattern_1344"
}

function test_5000(){
    pattern_5000=$(generate_pattern 5000)
    long_char_test "$pattern_5000"
}

function automatic_tests() {
    test_448
    test_896
}

function manual_tests() {
    test_1344
    test_5000
}

function layout_uk_macos_tests(){
    test_full_uk_kb
}

function layout_us_macos_tests(){
    test_full_us_kb
}

function script_tests(){
    echo "Running script tests..."

    echo "Run test_script.txt? y/n"
    read -p "Enter your choice: " choice
    if [[ "$choice" == "y" ]]; then
        python send_ble.py --device "$DEVICE" --layout "$MAIN_LAYOUT" --os "$MAIN_OS" --script test_script.txt
    fi

    echo "Run test_script_long.txt? y/n"
    read -p "Enter your choice: " choice
    if [[ "$choice" == "y" ]]; then
        python send_ble.py --device "$DEVICE" --layout "$MAIN_LAYOUT" --os "$MAIN_OS" --script test_script_long.txt
    fi

    echo "Run test_script_speed.txt? y/n"
    read -p "Enter your choice: " choice
    if [[ "$choice" == "y" ]]; then
        python send_ble.py --device "$DEVICE" --layout "$MAIN_LAYOUT" --os "$MAIN_OS" --script test_script_speed.txt
    fi
}

function menu() {
    echo "Select a test to run:"
    echo "1) Automatic tests (448 and 896 chars)"
    echo "2) Manual tests (1344 and 5000 chars)"
    echo "3) Full UK keyboard test (make sure you have the UK layout set in your OS)"
    echo "4) Full US keyboard test (make sure you have the US layout set in your OS)"
    echo "5) Script tests"
    read -p "Enter your choice: " choice

    case $choice in
        1) automatic_tests ;;
        2) manual_tests ;;
        3) layout_uk_macos_tests ;;
        4) layout_us_macos_tests ;;
        5) script_tests ;;
        *) echo "Invalid choice. Please select a valid option." ;;
    esac
}

#layout_us_macos_tests
#layout_uk_macos_tests
#automatic_tests
#manual_tests

menu

