A **fork** of fork of InfiCam from Netman that adds support for raw camera modes. 

## Features of this fork:
- Added support for P2 Pro cameras. (I only have a P2 Pro, so it’s the only model I was able to add and test support for.)
- Added a Share button that captures an image from the thermal camera and lets you share it wherever you need.
- Added a Thermal-Time Chart that plots temperature values (min, max, and center point) over time:
    1st press — starts recording the plot.
    2nd press — stops recording.
    3rd press — clears the current plot.
    4rd press — starts recording the plot...etc. 
    In settings you can change sample rate of capturing thermal measurements for chart.
    You can also (save) export chart separately from thermal image\video.
  <img width="2400" height="1080" alt="image" src="https://github.com/user-attachments/assets/f0ff7dd7-47ab-4f51-851e-003cbe32e1c7" />

- Added Web Control. You can now remotely view the thermal camera from any device with a web browser connected to the same Wi-Fi network using the generated IP address. This allows you to mount your phone above the object being tested and perform thermal analysis remotely from a computer, as well as save the results as videos or images. (The Thermal-Time Chart is not yet available in the web interface.)
  <img width="1193" height="992" alt="img" src="https://github.com/user-attachments/assets/1730f68c-1883-448c-8f01-0905b1404b35" />
  
- Minor layout fixes when switching between portrait and landscape orientation.
- Added the option to adjust the palette range by clicking on the gradient. 


Downloads available at:
https://github.com/diminDDL/InfiCamPlus/releases

Original:
https://gitlab.com/netman69/inficam

Desktop Python Script:
https://github.com/diminDDL/IR-Py-Thermal

## Contributing
The primary language of this repository is English.
Please write issues, discussions, pull requests, and comments in English.
**Discussions in other languages will be deleted.**

If you wish to add support for a camera that is not yet supported, please provide the VID and PID as well as any other information in an issue. Ideally you should be ready to work on the code yourself to add support for something as we can't work on cameras we don't have physically.

## Camera Model Support Chart
### Legend
✅ - Fully supported

🆗 - Quite usable, but may have some small quirks

🟨 - Works but has quirks

🟥 - Doesn't work


| Model | VID:PID | Supported | Note | See More |
| ----- | ------- | --------- | ---- | -------- |
| T2S+ v1  | 1514:xxxx | ✅ | Working in v1.0.5+. | [InfiCam](https://gitlab.com/netman69/inficam) |
| T2S+ v2  | 04b4:0100 | ✅ | Working in v1.0.4+. | [#2](https://github.com/diminDDL/InfiCamPlus/issues/2), [#18](https://github.com/diminDDL/InfiCamPlus/pull/18) |
| P2 Pro   | 0bda:5830 | ✅ | Working in v1.0.5+. | Users report it working in v1.0.3. Broken in v1.0.4+ | [#1](https://github.com/diminDDL/InfiCamPlus/issues/1), [#11](https://github.com/diminDDL/InfiCamPlus/pull/11) |
| HT301    | 1514:0001 | 🟥 | Not supported at this time, PRs are welcome | [#5](https://github.com/diminDDL/InfiCamPlus/issues/5) |
| UTi261M/UTi722M | 0bda:5830 | 🟥 | Not supported at this time, PRs are welcome | [#7](https://github.com/diminDDL/InfiCamPlus/issues/7) |
| HT820| 0bda:5840 | ✅ | Users reported it working. | [#12](https://github.com/diminDDL/InfiCamPlus/issues/12) |


