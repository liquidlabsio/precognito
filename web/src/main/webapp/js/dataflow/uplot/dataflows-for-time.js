let flowData = [
                                 ['Correlation', 'Register', 'Process', 'Load','Complete', 'Acknowledge'],
                                 ['txn-1000', 1000, 400, 200,1000, 400],
                                 ['txn-1222', 1170, 460, 250, 1000, 400],
                                 ['txn-133300', 660, 1120, 300, 1000, 400],
                                 ['txn-101gesr00', 1030, 540, 350, 1000, 400],
                                 ['txn-2000', 1000, 400, 200,1000, 400],
                                 ['txn-3222', 1170, 460, 250, 1000, 400],
                                 ['txn-433300', 660, 1120, 300, 1000, 400],
                                 ['txn-401gesr00', 1030, 540, 350, 1000, 400],
                                 ['txn-4000', 1000, 400, 200,1000, 400],
                                 ['txn-4222', 1170, 460, 250, 1000, 400],
                                 ['txn-533300', 660, 1120, 300, 1000, 400],
                                 ['txn-601gesr00', 1030, 540, 350, 1000, 400],
                                 ['txn-7000', 1000, 400, 200,1000, 400],
                                 ['txn-8222', 1170, 460, 250, 1000, 400],
                                 ['txn-933300', 660, 1120, 300, 1000, 400],
                                 ['txn-001gesr00', 1030, 540, 350, 1000, 400]
                               ];

class DataflowsForTime {

    load(element, rest, callback) {
        this.element = element;
        this.rest = rest;
        this.callback = callback;
        this.loadGChart();
    }
    loadGChart() {
        google.charts.load('current', {'packages':['corechart']});
        google.charts.setOnLoadCallback(this.loaded1.bind({ self: this }));
    }
    loaded1() {
        var options = {
          isStacked: true,
          bars: 'horizontal', // Required for Material Bar Charts.
          width:'1200',
          height:'400',
           colors: [ '#4285f4','#689df6','#8eb6f8','#b3cefb','#d9e7fd', '#1b9e77', '#d95f02', '#7570b3'],

        };


        self = this.self;

        self.options = options;
        self.chart = new google.visualization.BarChart(self.element);
        google.visualization.events.addListener(self.chart, 'click', self.drilldown.bind({self:self}));

        self.setData(self, flowData)
    }
    drilldown() {
        self = this.self;
        if (self.chart.getSelection().length > 0) {
          console.log("Item Selected:" + self.chart.getSelection()[0].row + " col:" + self.chart.getSelection()[0].column)
          console.log("Getting: UID:" + self.dataTable.getFormattedValue(self.chart.getSelection()[0].row,0))
          self.callback(self.dataTable.getFormattedValue(self.chart.getSelection()[0].row,0));
        }
    }

    setData(self, data) {
        self.dataTable = google.visualization.arrayToDataTable(data);
        self.chart.draw(self.dataTable, self.options);
    }

}