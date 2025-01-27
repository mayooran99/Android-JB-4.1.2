/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

#include "Log.h"
#include "Settings.h"
#include "StringUtil.h"
#include "Report.h"


Report* Report::mInstance = NULL;

Report* Report::Instance(const char* dirName)
{
    if (mInstance == NULL) {
        mInstance = new Report();
        ASSERT(mInstance->init(dirName));
    }
    return mInstance;
}
void Report::Finalize()
{
    delete mInstance;
    mInstance = NULL;
}


Report::Report()
{

}

Report::~Report()
{
    writeReport();
}

bool Report::init(const char* dirName)
{
    if (dirName == NULL) {
        return true;
    }
    android::String8 report;
    if (report.appendFormat("%s/report.xml", dirName) != 0) {
        return false;
    }
    Settings::Instance()->addSetting(Settings::EREPORT_FILE, report);
    return FileUtil::init(report.string());
}

void Report::printf(const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    FileUtil::doVprintf(false, -1, fmt, ap);
    va_end(ap);
}

void Report::addCasePassed(const android::String8& name)
{
    mPassedCases.push_back(name);
}

void Report::addCaseFailed(const android::String8& name)
{
    mFailedCases.push_back(name);
}

void Report::writeReport()
{
    printf("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>");
    printf("<audio-test-results-report report-version=\"1\" creation-time=\"%s\">",
            Settings::Instance()->getSetting(Settings::EREPORT_TIME).string());
    printf("  <verifier-info version-name=\"1\" version-code=\"1\" />");
    printf("  <device-info>");
    printf("    %s", Settings::Instance()->getSetting(Settings::EDEVICE_INFO).string());
    printf("  </device-info>");
    printf("  <audio-test-results xml=\"%s\">",
            Settings::Instance()->getSetting(Settings::ETEST_XML).string());
    std::list<android::String8>::iterator it;
    for (it = mFailedCases.begin(); it != mFailedCases.end(); it++) {
        printf("    <test title=\"%s\" result=\"fail\" />", it->string());
    }
    for (it = mPassedCases.begin(); it != mPassedCases.end(); it++) {
        printf("    <test title=\"%s\" result=\"pass\" />", it->string());
    }
    printf("  </audio-test-results>");
    printf("</audio-test-results-report>");
}
