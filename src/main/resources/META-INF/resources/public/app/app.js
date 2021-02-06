import {log} from "./utils/promise-helpers.js";
import {containerService} from "./services/container-service.js";
import {takeUntil, debounceTime, partialize, pipe} from "./utils/operators.js";

const operations = pipe(
    partialize(takeUntil, 3),
    partialize(debounceTime, 200)
)

const action = operations(() =>
    containerService
        .filterById('4197b499188c')
        .then(log)
        .then(containers => console.log(containers))
        .catch(console.log)
);

const action2 = operations(() =>
    containerService
        .listAll()
        .then(containers => log(containers[0].command))
        .then(log)
);


document
    .querySelector('#containerList')
    .onclick = action2;



