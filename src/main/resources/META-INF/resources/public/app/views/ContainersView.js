import {getContainersService} from "../services/ContainerService.js";
import {stopContainer, removeContainer, startContainer, isUpContainer} from "../controller/ContainerController.js";

const bodyDataTable = document.querySelector("[data-table]");
const actionStop = "stop";
const actionRemove = "remove";
const actionStart = "start"

function showContainer(containerId, image, command, created, status, ports, names) {

    const line = document.createElement('tr')

    line.innerHTML = `

        <td>${containerId}</td>
        <td>${image}</td>
        <td>${command}</td>
        <td>${created}</td>
        <td>${status}</td>
        <td>${ports}</td>
        <td>${names}</td>
    `;

    if (isUpContainer(status)) line.appendChild(createButton(actionStop, containerId));
    if (!isUpContainer(status)) line.appendChild(createButton(actionStart, containerId));
    line.appendChild(createButton(actionRemove, containerId));


    return line;
}

function createButton(label, id) {

    let htmlButtonElement = document.createElement("button");
    let textNode = document.createTextNode(label);


    htmlButtonElement.id = "btn-" + label;
    if (label === actionStart) {
        htmlButtonElement.className = "btn btn-primary";
    } else if (label === actionRemove) {
        htmlButtonElement.className = "btn btn-danger";
    } else {
        htmlButtonElement.className = "btn btn-warning";
    }

    htmlButtonElement.setAttribute('style', 'margin: 3px');

    htmlButtonElement.appendChild(textNode);

    htmlButtonElement.addEventListener("click", () => {
        if (label === actionRemove) {
            removeContainer(id);
        } else if (label === actionStop) {
            stopContainer(id);
        } else {
            startContainer(id);
        }
    })

    return htmlButtonElement;
}

getContainersService()
    .then(show => {
        show.forEach(index => {
            bodyDataTable.appendChild(showContainer(index.containerId,
                index.image,
                index.command,
                index.created,
                index.status,
                index.ports,
                index.names,
            ))
        })
    })

export function showLoader() {
    // let s = '<div class="spinner-border"></div>';
    $('.table-responsive').append('<div class="loader">' + '</div>');

    document.getElementById('containerTable').style.pointerEvents = "none";
}

// export function showLoader(){
//     document.getElementById('dc-loader').style.display = "block";
//     document.getElementById('containerTable').style.display = "none";
//
// }

// export function stopLoader(){
//     document.getElementById('dc-loader').style.display = "none";
//     document.getElementById('containerTable').style.display = "block";
// }
