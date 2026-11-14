#pragma once
#include <memory>

#include "capture.h"

class Session;

// A screenshot for the phone (msg::SCREENSHOT): made into a PNG and sent like a file of kind fkind::SCREENSHOT on a
// thread of its own, or SCREENSHOT_FAILED when there's no picture.
void SendShot(std::shared_ptr<Session> s, Shot&& shot);

// The picture as a PNG (24-bit, no alpha), by Windows Imaging Component. Needs COM on the calling thread.
bool EncodePng(const Shot& shot, Bytes& png, std::string& why);
