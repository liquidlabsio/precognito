
// column-highlights the hovered x index
function columnHighlightPlugin({ className, style = {backgroundColor: "rgba(51,204,255,0.3)"} } = {}) {
    let underEl, overEl, highlightEl, currIdx;

    function init(u) {
        underEl = u.root.querySelector(".under");
        overEl = u.root.querySelector(".over");

        highlightEl = document.createElement("div");

        className && highlightEl.classList.add(className);

        uPlot.assign(highlightEl.style, {
            pointerEvents: "none",
            display: "none",
            position: "absolute",
            left: 0,
            top: 0,
            height: "100%",
            ...style
        });

        underEl.appendChild(highlightEl);

        // show/hide highlight on enter/exit
        overEl.addEventListener("mouseenter", () => {highlightEl.style.display = null;});
        overEl.addEventListener("mouseleave", () => {highlightEl.style.display = "none";});
    }

    function update(u) {
        if (currIdx !== u.cursor.idx) {
            currIdx = u.cursor.idx;

            let [iMin, iMax] = u.series[0].idxs;

            const dx    = iMax - iMin;
            const width = (u.bbox.width / dx) / devicePixelRatio;
            const xVal  = u.scales.x.distr == 2 ? currIdx : u.data[0][currIdx];
            const left  = u.valToPos(xVal, "x") - width / 2;

            highlightEl.style.transform = "translateX(" + Math.round(left) + "px)";
            highlightEl.style.width = Math.round(width) + "px";
        }
    }

    return {
        opts: (u, opts) => {
            uPlot.assign(opts, {
                cursor: {
                    x: false,
                    y: false,
                }
            });
        },
        hooks: {
            init: init,
            setCursor: update,
        }
    };
}


function ladderRenderPlugin({ gap = 2, shadowColor = "#000000", bodyMaxWidth = 50 } = {}) {

    function renderLadderY(ladderY, index) {
            let ladderAsY = this.u.valToPos(ladderY,   "y", true);

            let ladderValue = this.u.data[2][this.i][index]
            let ladderPercent = ladderValue / this.maxHeatValue;

            let colorHeat = parseInt(255 * ladderPercent);
           this.u.ctx.fillStyle = "rgba(50,50," + colorHeat + ",0.5)"

            this.u.ctx.fillRect(
                Math.round(this.bodyX),
                Math.round(ladderAsY),
                Math.round(this.bodyWidth),
                Math.round(5),
            );
    }

    function drawSeries(u) {
        u.ctx.save();

        let [iMin, iMax] = u.series[0].idxs;
        let columnWidth  = u.bbox.width / (iMax - iMin);
        let bodyWidth    = Math.min(bodyMaxWidth, columnWidth - gap);

        for (let i = iMin; i <= iMax; i++) {
            let xVal         = u.scales.x.distr == 2 ? i : u.data[0][i];
            let timeAsX      = u.valToPos(xVal,  "x", true);
            let bodyX        = timeAsX - (bodyWidth / 2);
            let ladderY         = u.data[1][i];

            ladderY.forEach(renderLadderY.bind({ i:i, u:u, xVal:xVal, bodyX:bodyX, bodyWidth:bodyWidth, maxHeatValue: u.data[3][1]}));
        }
        u.ctx.restore();
    }

    return {
        hooks: {
            draw: drawSeries,
        }
    };
}

const data = [
    // dates
    [1546300800,1546387200,1546473600,1546560000,1546819200,1546905600,1546992000,1547078400,1547164800,1547424000,1547510400,1547596800],

    //  latency ladder
    [
        [80,  50,  43],
        [10,  12,  14,  18,  30,  50],
        [70,  90,  13,  10,  12,  13],
        [170, 190, 113, 110, 112, 113],
        [270, 290, 213, 110, 112, 113],
        [80,  50,  43],
        [10,  12,  14,  18,  30,  50],
        [70,  90,  13,  10,  12,  13],
        [170, 190, 113, 110, 112, 113],
        [270, 290, 213, 110, 112, 113],
        [220, 222, 224, 228, 230, 240],
        [270, 290, 213, 210, 212, 213],

    ],

    // count ladder to match latency
    [
        [1,  3,   5],
        [7,  9,   5,   6, 7,  8],
        [17, 119, 115, 116, 117, 118],
        [27, 29, 25, 226, 227, 228],
        [17, 19, 15, 216, 227, 288],
        [12,  32,   52],
        [217,  219,   252,   216, 217,  218],
        [17, 19, 15, 16, 17, 18],
        [27, 29, 25, 26, 27, 28],
        [17, 19, 15, 16, 27, 88],
        [17, 19, 15, 16, 17, 18],
        [27, 29, 25, 26, 27, 28],
    ],

    // colour range
    [10, 256]

];

const fmtDate = uPlot.fmtDate("{YYYY}-{MM}-{DD} {h}:{mm}:{ss}");
const tzDate = ts => uPlot.tzDate(new Date(ts * 1e3), "Etc/UTC");

const opts = {
    width: 1440,
    height: 600,
    title: "Latency heatmap ladder",
    tzDate,
    plugins: [
       columnHighlightPlugin(),
       ladderRenderPlugin(),
    ],
      series: [
            {},
            {
                paths: () => null,
              points: {show: false},
            },
              {
                paths: () => null,
              points: {show: false},
            },
//            { }

        ],
    scales: {
        y: {
            auto: false,
            range: u => {
                let [i0, i1] = u.series[0].idxs;

                let min = Infinity;
                let max = -Infinity;
                let heatmapMax = 0;

                // find min/max y values for all non-null values in shown series
                for (let i = i0; i <= i1; i++) {
                    let yVal = u.data[1][i];
                    if (yVal != null) {
                        for (let yy = 0; yy < yVal.length; yy++) {
                          min = Math.min(min, yVal[yy]);
                          max = Math.max(max, yVal[yy]);
                        }
                    }
                }
                return [min, max];
            },
        },
    }
};

let u = new uPlot(opts, data, document.body);